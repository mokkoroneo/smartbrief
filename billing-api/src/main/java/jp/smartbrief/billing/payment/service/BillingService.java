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
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
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
                subscription_expires_at = CASE
                    WHEN :expiresAt IS NULL THEN NULL
                    WHEN subscription_expires_at > :expiresAt THEN subscription_expires_at
                    ELSE :expiresAt
                END
            WHERE id = :userId
            """;

    private static final String CHECK_USER_EXISTS_SQL =
            "SELECT COUNT(*) FROM users WHERE id = :userId";

    private static final String EXTEND_SUBSCRIPTION_SQL = """
            UPDATE users
            SET plan_type = :planType,
                subscription_expires_at = CASE
                    WHEN subscription_expires_at > :periodEnd THEN subscription_expires_at
                    ELSE :periodEnd
                END
            WHERE id = :userId AND stripe_customer_id = :customerId
            """;

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
        if (userId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "User ID is required"));
        }

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> Mono.fromCallable(() -> {
                    var params = buildCheckoutParams(userId, email, user.getStripeCustomerId());
                    try {
                        return createStripeSession(params).getUrl();
                    } catch (InvalidRequestException e) {
                        if (params.getCustomer() == null || !"resource_missing".equals(e.getCode())
                                || !"customer".equals(e.getParam())) {
                            throw e;
                        }
                        log.warn("Retrying Stripe Checkout after stale customer id for userId={}", userId);
                        return createStripeSession(buildCheckoutParams(userId, email, null)).getUrl();
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    com.stripe.param.checkout.SessionCreateParams buildCheckoutParams(Long userId, String email,
            String stripeCustomerId) {
        var builder = com.stripe.param.checkout.SessionCreateParams.builder()
                .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(frontendUrl + "/payment/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(frontendUrl + "/")
                .addLineItem(
                        LineItem.builder()
                                .setQuantity(1L)
                                .setPrice(premiumPriceId)
                                .build()
                )
                .setClientReferenceId(String.valueOf(userId))
                .putMetadata("userId", String.valueOf(userId));

        // Stripe accepts either customer or customer_email. Reuse the saved customer to prevent duplicates.
        if (stripeCustomerId != null && !stripeCustomerId.isBlank()) {
            builder.setCustomer(stripeCustomerId.strip());
        } else {
            builder.setCustomerEmail(email);
        }
        return builder.build();
    }

    protected com.stripe.model.checkout.Session createStripeSession(
            com.stripe.param.checkout.SessionCreateParams params) throws StripeException {
        return com.stripe.model.checkout.Session.create(params);
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

    public Mono<WebhookProcessResult> extendSubscriptionFromInvoice(
            String eventId, String customerId, LocalDateTime periodEnd) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");

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

                            Mono<User> targetUser = customerId == null || customerId.isBlank()
                                    ? Mono.empty()
                                    : userRepository.findByStripeCustomerId(customerId);
                            return targetUser
                                    .flatMap(user -> extendInvoiceExpiry(user.getId(), customerId, periodEnd))
                                    .map(ignored -> WebhookProcessResult.PROCESSED)
                                    .switchIfEmpty(Mono.fromSupplier(() -> {
                                        txStatus.setRollbackOnly();
                                        return WebhookProcessResult.USER_NOT_FOUND;
                                    }));
                        })
        ).single();
    }

    private Mono<Long> extendInvoiceExpiry(long userId, String customerId, LocalDateTime periodEnd) {
        return databaseClient.sql(EXTEND_SUBSCRIPTION_SQL)
                .bind("planType", User.Plan.PREMIUM.name())
                .bind("periodEnd", periodEnd)
                .bind("userId", userId)
                .bind("customerId", customerId)
                .fetch()
                .rowsUpdated()
                .flatMap(updatedRows -> {
                    if (updatedRows > 0) {
                        return Mono.just(userId);
                    }
                    // A no-op update may report zero changed rows. Check the current customer link.
                    return databaseClient.sql("""
                            SELECT id FROM users
                            WHERE id = :userId AND stripe_customer_id = :customerId
                            FOR UPDATE
                            """)
                            .bind("userId", userId)
                            .bind("customerId", customerId)
                            .map((row, metadata) -> row.get("id", Long.class))
                            .one();
                });
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
