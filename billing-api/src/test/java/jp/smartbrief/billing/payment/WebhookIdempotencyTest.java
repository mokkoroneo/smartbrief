package jp.smartbrief.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.payment.domain.WebhookProcessResult;
import jp.smartbrief.billing.payment.service.BillingService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "PT10S")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "SPRING_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "SPRING_R2DBC_USERNAME=root",
    "SPRING_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "AOZORA_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "AOZORA_R2DBC_USERNAME=root",
    "AOZORA_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "JWT_SECRET_KEY=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLXB1cnBvc2Utb25seQ==",
    "JWT_EXPIRATION=3600000",
    "STRIPE_API_KEY=test-stripe-api-key-for-testing-only",
    "stripe.api.key=test-stripe-api-key-for-testing-only",
    "STRIPE_PRICE_ID=price_test_dummy_for_testing",
    "STRIPE_WEBHOOK_SECRET=whsec_test_secret_for_integration_test",
    "spring.sql.init.mode=always"
})
class WebhookIdempotencyTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_integration_test";

    @Autowired
    private BillingService billingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private WebTestClient webTestClient;

    private User targetUser;

    @BeforeEach
    void setUp() {
        databaseClient.sql("DELETE FROM user_book_history").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM processed_stripe_events").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();

        targetUser = userRepository.save(User.builder()
                .username("webhook_target_user")
                .email("webhook_target@example.com")
                .password("initial_password_hash")
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .isVerified(true)
                .build()).block();
    }

    @Test
    void concurrentDuplicateWebhookArrival_shouldNotThrow500AndApplyUpdateOnce() {
        databaseClient.sql("UPDATE users SET daily_view_count = 7, password = 'updated_password_hash' WHERE id = :uid")
                .bind("uid", targetUser.getId())
                .fetch()
                .rowsUpdated()
                .block();

        String eventId = "evt_concurrent_same_001";
        String userIdStr = String.valueOf(targetUser.getId());
        Sinks.Empty<Void> startGate = Sinks.empty();

        var twoArrivalsMono = Flux.range(1, 2)
                .flatMap(i -> startGate.asMono()
                        .subscribeOn(Schedulers.parallel())
                        .then(billingService.updateSubscriptionFromWebhook(
                                eventId, userIdStr, User.Plan.PREMIUM, "cus_concurrent_001")),
                        2)
                .collectList();

        Schedulers.parallel().schedule(() -> startGate.tryEmitEmpty());

        List<WebhookProcessResult> results = twoArrivalsMono.block();
        assertThat(results).containsExactlyInAnyOrder(
                WebhookProcessResult.PROCESSED,
                WebhookProcessResult.DUPLICATE
        );

        databaseClient.sql("SELECT plan_type, stripe_customer_id, daily_view_count, password FROM users WHERE id = :uid")
                .bind("uid", targetUser.getId())
                .map((row, meta) -> {
                    assertThat(row.get("plan_type", String.class)).isEqualTo("PREMIUM");
                    assertThat(row.get("stripe_customer_id", String.class)).isEqualTo("cus_concurrent_001");
                    assertThat(row.get("daily_view_count", Integer.class)).isEqualTo(7);
                    assertThat(row.get("password", String.class)).isEqualTo("updated_password_hash");
                    return true;
                })
                .one()
                .block();
    }

    @Test
    void checkoutWebhookPreservesLaterSubscriptionExpiry() {
        LocalDateTime existingExpiry = LocalDateTime.now().plusMonths(2).withNano(0);
        String customerId = "cus_checkout_future_expiry";
        databaseClient.sql("""
                UPDATE users
                SET plan_type = 'PREMIUM',
                    stripe_customer_id = :customerId,
                    subscription_expires_at = :expiresAt
                WHERE id = :userId
                """)
                .bind("customerId", customerId)
                .bind("expiresAt", existingExpiry)
                .bind("userId", targetUser.getId())
                .fetch()
                .rowsUpdated()
                .block();

        WebhookProcessResult result = billingService.updateSubscriptionFromWebhook(
                "evt_checkout_future_expiry", String.valueOf(targetUser.getId()), User.Plan.PREMIUM, customerId)
                .block();

        assertThat(result).isEqualTo(WebhookProcessResult.PROCESSED);
        LocalDateTime savedExpiry = databaseClient.sql("SELECT subscription_expires_at FROM users WHERE id = :userId")
                .bind("userId", targetUser.getId())
                .map((row, metadata) -> row.get("subscription_expires_at", LocalDateTime.class))
                .one()
                .block();
        assertThat(savedExpiry).isEqualTo(existingExpiry);
    }

    @Test
    void duplicateWebhookHttpEndpoint_shouldReturn200OkNot404() {
        String payload = buildCheckoutCompletedPayload("evt_http_dup_001", String.valueOf(targetUser.getId()), "cus_http_001");
        String sigHeader = signPayload(payload, WEBHOOK_SECRET);

        webTestClient.post()
                .uri("/api/v1/stripe/webhook")
                .header("Stripe-Signature", sigHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Success");

        webTestClient.post()
                .uri("/api/v1/stripe/webhook")
                .header("Stripe-Signature", sigHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Already processed");
    }

    @Test
    void userNotFoundAndIgnoredEvent_shouldReturn200LogErrorAndRollbackDedupe(CapturedOutput output) {
        String missingUserPayload = buildCheckoutCompletedPayload("evt_missing_user_999", "99999999", "cus_missing_999");
        String missingUserSig = signPayload(missingUserPayload, WEBHOOK_SECRET);

        webTestClient.post()
                .uri("/api/v1/stripe/webhook")
                .header("Stripe-Signature", missingUserSig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(missingUserPayload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("User not found");

        assertThat(output.getOut()).contains("ERROR").contains("Target user not found!").contains("evt_missing_user_999");

        Long recordedMissing = databaseClient.sql("SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = 'evt_missing_user_999'")
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .block();
        assertThat(recordedMissing).isZero();

        String ignoredPayload = """
                {
                  "id": "evt_ignored_001",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_123", "object": "payment_intent" } }
                }
                """;
        webTestClient.post()
                .uri("/api/v1/stripe/webhook")
                .header("Stripe-Signature", signPayload(ignoredPayload, WEBHOOK_SECRET))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ignoredPayload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Event received and ignored");
    }

    @Test
    void planUpdateFailure_shouldRollbackDedupeInsertAndSucceedOnRetry() {
        String eventId = "evt_rollback_test_001";
        String userIdStr = String.valueOf(targetUser.getId());
        String tooLongCustomerId = "cus_" + "x".repeat(300);

        StepVerifier.create(billingService.updateSubscriptionFromWebhook(
                        eventId, userIdStr, User.Plan.PREMIUM, tooLongCustomerId))
                .expectError()
                .verify();

        Long recordedEvents = databaseClient.sql("SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = :eid")
                .bind("eid", eventId)
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .block();
        assertThat(recordedEvents).isZero();

        WebhookProcessResult retryResult = billingService.updateSubscriptionFromWebhook(
                eventId, userIdStr, User.Plan.PREMIUM, "cus_retry_valid_001").block();
        assertThat(retryResult).isEqualTo(WebhookProcessResult.PROCESSED);
    }

    private String buildCheckoutCompletedPayload(String eventId, String userId, String customerId) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "cs_test_session_001",
                      "object": "checkout.session",
                      "customer": "%s",
                      "metadata": {
                        "userId": "%s"
                      }
                    }
                  }
                }
                """.formatted(eventId, customerId, userId);
    }

    private String signPayload(String payload, String secret) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign test webhook payload", e);
        }
    }
}
