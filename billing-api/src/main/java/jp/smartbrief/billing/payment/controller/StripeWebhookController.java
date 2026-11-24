package jp.smartbrief.billing.payment.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.payment.domain.WebhookProcessResult;
import jp.smartbrief.billing.payment.service.BillingService;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoiceLineItemCollection;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    @Value("${stripe.webhook-secret}")
    private String endpointSecret;

    private final BillingService billingService;

    @PostMapping("/webhook")
    public Mono<ResponseEntity<String>> handleStripeWebhook(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .flatMap(payload -> {
                    String sigHeader = request.getHeaders().getFirst("Stripe-Signature");

                    if (sigHeader == null) {
                        log.error("★ [Webhook] Missing Stripe-Signature header");
                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing Signature"));
                    }

                    Event event;
                    try {
                        event = Webhook.constructEvent(payload, sigHeader, Objects.requireNonNull(endpointSecret));
                        log.info("★ [Webhook] Signature Verified. Type: {}", event.getType());
                    } catch (Exception e) {
                        log.error("★ [Webhook] Signature verification failed!", e);
                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid Signature"));
                    }

                    if ("checkout.session.completed".equals(event.getType())) {
                        try {
                            Session session = (Session) event.getDataObjectDeserializer().deserializeUnsafe();
                            if (session != null) {
                                String userIdStr = session.getMetadata() != null ? session.getMetadata().get("userId") : null;
                                String customerId = session.getCustomer();

                                if (userIdStr == null || userIdStr.isBlank()) {
                                    log.error("★ [Webhook] userId missing in session metadata! EventID: {}, CustomerID: {}",
                                            event.getId(), customerId);
                                    return Mono.just(ResponseEntity.ok("User ID missing"));
                                }

                                log.info("★ [Webhook] Processing event. UserID: {}, CustomerID: {}, EventID: {}",
                                        userIdStr, customerId, event.getId());

                                return billingService.updateSubscriptionFromWebhook(
                                                event.getId(), userIdStr, User.Plan.PREMIUM, customerId)
                                        .map(result -> mapResultToResponse(result, event.getId(), userIdStr, customerId));
                            }
                        } catch (EventDataObjectDeserializationException e) {
                            log.error("★ [Webhook] Deserialization failed", e);
                            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Deserialization Error"));
                        }
                    }

                    if ("invoice.paid".equals(event.getType())) {
                        return handleInvoicePaid(event);
                    }

                    return Mono.just(mapResultToResponse(WebhookProcessResult.IGNORED, event.getId(), null, null));
                });
    }

    private Mono<ResponseEntity<String>> handleInvoicePaid(Event event) {
        Invoice invoice;
        try {
            invoice = (Invoice) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            log.error("Stripe invoice deserialization failed. EventID: {}", event.getId(), e);
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Deserialization Error"));
        }

        String customerId = invoice == null ? null : invoice.getCustomer();
        InvoiceLineItemCollection lines = invoice == null ? null : invoice.getLines();
        OptionalLong latestEnd = lines == null || lines.getData() == null
                ? OptionalLong.empty()
                : lines.getData().stream()
                        .filter(Objects::nonNull)
                        .map(InvoiceLineItem::getPeriod)
                        .filter(Objects::nonNull)
                        .map(InvoiceLineItem.Period::getEnd)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .max();
        if (latestEnd.isEmpty()) {
            log.info("Stripe invoice ignored because its lines contain no period end. EventID: {}", event.getId());
            return Mono.just(mapResultToResponse(WebhookProcessResult.IGNORED, event.getId(), null, customerId));
        }

        LocalDateTime periodEnd = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(latestEnd.getAsLong()), ZoneId.systemDefault());
        return billingService.extendSubscriptionFromInvoice(event.getId(), customerId, periodEnd)
                .map(result -> mapResultToResponse(result, event.getId(), null, customerId));
    }

    private ResponseEntity<String> mapResultToResponse(
            WebhookProcessResult result, String eventId, String userIdStr, String customerId) {
        return switch (result) {
            case PROCESSED -> {
                log.info("★ [Webhook] DB Updated successfully. UserID: {}, EventID: {}", userIdStr, eventId);
                yield ResponseEntity.ok("Success");
            }
            case DUPLICATE -> {
                log.info("★ [Webhook] Event already processed. EventID: {}", eventId);
                yield ResponseEntity.ok("Already processed");
            }
            case USER_NOT_FOUND -> {
                log.error("★ [Webhook] Target user not found! UserID: {}, CustomerID: {}, EventID: {}",
                        userIdStr, customerId, eventId);
                yield ResponseEntity.ok("User not found");
            }
            case IGNORED -> ResponseEntity.ok("Event received and ignored");
        };
    }
}
