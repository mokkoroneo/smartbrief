package jp.smartbrief.billing.payment.service;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.Stripe;
import com.stripe.model.billingportal.Session;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;

import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.payment.domain.WebhookProcessResult;
import jp.smartbrief.billing.payment.dto.BillingStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final String INSERT_IGNORE_EVENT_SQL =
            "INSERT IGNORE INTO processed_stripe_events (event_id) VALUES (:eventId)";

    private static final String UPDATE_SUBSCRIPTION_COLUMNS_SQL = """
            UPDATE users
            SET plan_type = :planType,
                stripe_customer_id = COALESCE(NULLIF(:stripeCustomerId, ''), stripe_customer_id),
                subscription_expires_at = :expiresAt
            WHERE id = :userId
            """;

    private static final String CHECK_USER_EXISTS_SQL =
            "SELECT COUNT(*) FROM users WHERE id = :userId";

    private final UserRepository userRepository;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    @Value("${stripe.api.key}")
    private String stripeApiKey;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${STRIPE_PRICE_ID}")
    private String premiumPriceId;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeApiKey;
    }

    public Mono<BillingStatusDto> getBillingStatus(String username) {
        return userRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .map(this::mapToStatusDto);
    }

    public Mono<String> createCheckoutSession(Long userId, String email) {
        return Mono.fromCallable(() -> {
            com.stripe.param.checkout.SessionCreateParams params = com.stripe.param.checkout.SessionCreateParams.builder()
                    .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomerEmail(email)
                    .setSuccessUrl(frontendUrl + "/payment/success?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(frontendUrl + "/")
                    .addLineItem(
                            LineItem.builder()
                                    .setQuantity(1L)
                                    .setPrice(premiumPriceId)
                                    .build()
                    )
                    .setClientReferenceId(String.valueOf(userId))
                    .putMetadata("userId", String.valueOf(userId))
                    .build();

            return com.stripe.model.checkout.Session.create(params).getUrl();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> createPortalSession(String customerId) {
        if (customerId == null || customerId.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "課金履歴が見つかりません"));
        }

        return Mono.fromCallable(() -> {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setCustomer(customerId)
                    .setReturnUrl(frontendUrl + "/settings")
                    .build();

            return Session.create(params).getUrl();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WebhookProcessResult> updateSubscriptionFromWebhook(
            String eventId, String userIdStr, User.Plan newPlan, String stripeCustomerId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(newPlan, "newPlan must not be null");

        final long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            return Mono.just(WebhookProcessResult.USER_NOT_FOUND);
        }

        LocalDateTime expiresAt = (newPlan == User.Plan.PREMIUM)
                ? LocalDateTime.now().plusMonths(1)
                : null;

        return transactionalOperator.execute(txStatus ->
                databaseClient.sql(INSERT_IGNORE_EVENT_SQL)
                        .bind("eventId", eventId)
                        .fetch()
                        .rowsUpdated()
                        .flatMap(insertedRows -> {
                            if (insertedRows == 0) {
                                log.info("Duplicate Stripe event ignored: {}", eventId);
                                return Mono.just(WebhookProcessResult.DUPLICATE);
                            }

                            DatabaseClient.GenericExecuteSpec updateSpec = databaseClient.sql(UPDATE_SUBSCRIPTION_COLUMNS_SQL)
                                    .bind("planType", newPlan.name())
                                    .bind("userId", userId);

                            updateSpec = (stripeCustomerId != null)
                                    ? updateSpec.bind("stripeCustomerId", stripeCustomerId)
                                    : updateSpec.bindNull("stripeCustomerId", String.class);

                            updateSpec = (expiresAt != null)
                                    ? updateSpec.bind("expiresAt", expiresAt)
                                    : updateSpec.bindNull("expiresAt", LocalDateTime.class);

                            return updateSpec.fetch()
                                    .rowsUpdated()
                                    .flatMap(updatedRows -> {
                                        if (updatedRows > 0) {
                                            log.info("Subscription updated: UserID={}, Plan={}, EventID={}", userId, newPlan, eventId);
                                            return Mono.just(WebhookProcessResult.PROCESSED);
                                        }

                                        return databaseClient.sql(CHECK_USER_EXISTS_SQL)
                                                .bind("userId", userId)
                                                .map((row, meta) -> row.get(0, Long.class))
                                                .one()
                                                .defaultIfEmpty(0L)
                                                .map(count -> {
                                                    if (count > 0) {
                                                        log.info("Subscription already up-to-date: UserID={}, Plan={}, EventID={}", userId, newPlan, eventId);
                                                        return WebhookProcessResult.PROCESSED;
                                                    }
                                                    txStatus.setRollbackOnly();
                                                    return WebhookProcessResult.USER_NOT_FOUND;
                                                });
                                    });
                        })
        ).single();
    }

    private BillingStatusDto mapToStatusDto(User user) {
        boolean isPremium = User.Plan.PREMIUM.name().equals(user.getPlanType())
                && (user.getSubscriptionExpiresAt() == null || user.getSubscriptionExpiresAt().isAfter(LocalDateTime.now()));

        return new BillingStatusDto(
                user.getUsername(),
                user.getPlanType(),
                isPremium,
                user.getSubscriptionExpiresAt() != null ? user.getSubscriptionExpiresAt().toString() : "N/A"
        );
    }
}