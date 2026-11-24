package jp.smartbrief.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "PT10S")
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
class StripeInvoicePaidTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_integration_test";
    private static final String CUSTOMER_ID = "cus_invoice_renewal";
    private static final Instant INITIAL_EXPIRY = Instant.parse("2030-03-01T12:00:00Z");
    private static final Instant RENEWED_EXPIRY = Instant.parse("2030-04-01T12:00:00Z");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private WebTestClient webTestClient;

    private long userId;

    @BeforeEach
    void setUp() {
        databaseClient.sql("DELETE FROM user_book_history").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM processed_stripe_events").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();

        userId = userRepository.save(User.builder()
                .username("invoice_renewal_user")
                .email("invoice_renewal@example.com")
                .password("initial_password_hash")
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .stripeCustomerId(CUSTOMER_ID)
                .subscriptionExpiresAt(localTime(INITIAL_EXPIRY))
                .isVerified(true)
                .build()).block().getId();
    }

    @Test
    void invoicePaidExtendsExpiryToLatestLineEndAndSetsPremium() {
        String lines = """
                {"period": {"end": %d}},
                {"period": {"end": %d}},
                {"period": {}}
                """.formatted(RENEWED_EXPIRY.getEpochSecond(), INITIAL_EXPIRY.getEpochSecond());

        postInvoice(buildInvoicePaidPayload("evt_invoice_renewal", CUSTOMER_ID, lines), "Success");

        assertThat(readSubscription()).isEqualTo(new Subscription("PREMIUM", localTime(RENEWED_EXPIRY)));
    }

    @Test
    void duplicateInvoiceReturnsAlreadyProcessedAndPreservesExpiry() {
        String eventId = "evt_invoice_duplicate";
        String payload = buildInvoicePaidPayload(eventId, CUSTOMER_ID, lineEndingAt(RENEWED_EXPIRY));
        postInvoice(payload, "Success");
        assertThat(readSubscription()).isEqualTo(new Subscription("PREMIUM", localTime(RENEWED_EXPIRY)));

        postInvoice(payload, "Already processed");

        assertThat(readSubscription()).isEqualTo(new Subscription("PREMIUM", localTime(RENEWED_EXPIRY)));
        assertThat(countRecordedEvents(eventId)).isEqualTo(1L);
    }

    @Test
    void unknownCustomerRollsBackEventRecord() {
        String eventId = "evt_invoice_unknown_customer";
        String payload = buildInvoicePaidPayload(eventId, "cus_invoice_unknown", lineEndingAt(RENEWED_EXPIRY));

        postInvoice(payload, "User not found");

        assertThat(countRecordedEvents(eventId)).isZero();
        assertThat(readSubscription()).isEqualTo(new Subscription("FREE", localTime(INITIAL_EXPIRY)));
    }

    @Test
    void olderInvoicePreservesLaterExpiry() {
        postInvoice(buildInvoicePaidPayload("evt_invoice_newer", CUSTOMER_ID, lineEndingAt(RENEWED_EXPIRY)), "Success");

        String eventId = "evt_invoice_older";
        postInvoice(buildInvoicePaidPayload(eventId, CUSTOMER_ID, lineEndingAt(INITIAL_EXPIRY)), "Success");

        assertThat(readSubscription()).isEqualTo(new Subscription("PREMIUM", localTime(RENEWED_EXPIRY)));
        assertThat(countRecordedEvents(eventId)).isEqualTo(1L);
    }

    @Test
    void invoiceWithoutLinePeriodEndIsIgnored() {
        String eventId = "evt_invoice_no_period_end";
        String payload = buildInvoicePaidPayload(eventId, CUSTOMER_ID, "{}, {\"period\": {}}");

        postInvoice(payload, "Event received and ignored");

        assertThat(countRecordedEvents(eventId)).isZero();
        assertThat(readSubscription()).isEqualTo(new Subscription("FREE", localTime(INITIAL_EXPIRY)));
    }

    private void postInvoice(String payload, String expectedResponse) {
        webTestClient.post()
                .uri("/api/v1/stripe/webhook")
                .header("Stripe-Signature", signPayload(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(expectedResponse);
    }

    private Subscription readSubscription() {
        return databaseClient.sql("SELECT plan_type, subscription_expires_at FROM users WHERE id = :userId")
                .bind("userId", userId)
                .map((row, metadata) -> new Subscription(row.get("plan_type", String.class),
                        row.get("subscription_expires_at", LocalDateTime.class)))
                .one()
                .block();
    }

    private Long countRecordedEvents(String eventId) {
        return databaseClient.sql("SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = :eventId")
                .bind("eventId", eventId)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .block();
    }

    private static LocalDateTime localTime(Instant timestamp) {
        return LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault());
    }

    private static String lineEndingAt(Instant periodEnd) {
        return "{\"period\": {\"end\": %d}}".formatted(periodEnd.getEpochSecond());
    }

    private static String buildInvoicePaidPayload(String eventId, String customerId, String lines) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "2024-06-20",
                  "type": "invoice.paid",
                  "data": {
                    "object": {
                      "id": "in_test_renewal",
                      "object": "invoice",
                      "customer": "%s",
                      "lines": {"object": "list", "data": [%s]}
                    }
                  }
                }
                """.formatted(eventId, customerId, lines);
    }

    private static String signPayload(String payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign test webhook payload", e);
        }
    }

    private record Subscription(String plan, LocalDateTime expiry) {
    }
}
