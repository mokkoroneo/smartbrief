package jp.smartbrief.billing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.AuthenticationException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BillingServiceCheckoutTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long USER_ID = 42L;
    private static final String EMAIL = "buyer@example.com";
    private static final String CUSTOMER = "cus_saved";
    private static final String FRONTEND_URL = "https://smartbrief.example";
    private static final String PRICE = "price_example";
    private static final String CHECKOUT_URL = "https://checkout.example/session";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final StubBillingService service = new StubBillingService(userRepository);

    @BeforeEach
    void configureCheckout() {
        ReflectionTestUtils.setField(service, "frontendUrl", FRONTEND_URL);
        ReflectionTestUtils.setField(service, "premiumPriceId", PRICE);
    }

    @Test
    void reusesTrimmedCustomerAndPreservesCheckoutSettings() {
        givenCustomerId(" \t" + CUSTOMER + "\n");

        StepVerifier.create(service.createCheckoutSession(USER_ID, EMAIL))
                .expectNext(CHECKOUT_URL)
                .expectComplete().verify(TIMEOUT);

        assertThat(service.attempts).singleElement().satisfies(params -> {
            assertThat(params.getCustomer()).isEqualTo(CUSTOMER);
            assertThat(params.getCustomerEmail()).isNull();
            assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
            assertThat(params.getSuccessUrl()).isEqualTo(FRONTEND_URL + "/payment/success?session_id={CHECKOUT_SESSION_ID}");
            assertThat(params.getCancelUrl()).isEqualTo(FRONTEND_URL + "/");
            assertThat(params.getLineItems()).singleElement().satisfies(item -> {
                assertThat(item.getPrice()).isEqualTo(PRICE);
                assertThat(item.getQuantity()).isEqualTo(1L);
            });
            assertThat(params.getClientReferenceId()).isEqualTo(Long.toString(USER_ID));
            assertThat(params.getMetadata()).isEqualTo(Map.of("userId", Long.toString(USER_ID)));
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void usesEmailWhenCustomerIsMissingOrBlank(String customerId) {
        givenCustomerId(customerId);

        StepVerifier.create(service.createCheckoutSession(USER_ID, EMAIL))
                .expectNext(CHECKOUT_URL)
                .expectComplete().verify(TIMEOUT);

        assertThat(service.attempts).singleElement().satisfies(this::assertEmailParams);
    }

    @Test
    void retriesStaleCustomerOnceWithEmailAndReturnsCheckoutUrl() {
        givenCustomerId(CUSTOMER);
        service.failures = List.of(invalidRequest("resource_missing", "customer"));

        StepVerifier.create(service.createCheckoutSession(USER_ID, EMAIL))
                .expectNext(CHECKOUT_URL)
                .expectComplete().verify(TIMEOUT);

        assertThat(service.attempts).hasSize(2);
        assertThat(service.attempts.get(0).getCustomer()).isEqualTo(CUSTOMER);
        assertThat(service.attempts.get(0).getCustomerEmail()).isNull();
        assertEmailParams(service.attempts.get(1));
    }

    @Test
    void doesNotRetryOtherStripeErrorCodes() {
        givenCustomerId(CUSTOMER);
        StripeException error = invalidRequest("parameter_invalid_string", "customer");
        service.failures = List.of(error);

        expectStripeFailure(error);

        assertThat(service.attempts).hasSize(1);
    }

    @Test
    void doesNotRetryStripeAuthenticationFailure() {
        givenCustomerId(CUSTOMER);
        StripeException error = new AuthenticationException("Invalid API key", "req_example", "api_key_expired", 401);
        service.failures = List.of(error);

        expectStripeFailure(error);

        assertThat(service.attempts).hasSize(1);
    }

    @Test
    void doesNotRetryWhenPriceIsMissing() {
        givenCustomerId(CUSTOMER);
        StripeException error = invalidRequest("resource_missing", "line_items[0][price]");
        service.failures = List.of(error);

        expectStripeFailure(error);

        assertThat(service.attempts).hasSize(1);
    }

    @Test
    void doesNotRetryWhenInitialAttemptUsesEmail() {
        givenCustomerId(null);
        StripeException error = invalidRequest("resource_missing", "customer");
        service.failures = List.of(error);

        expectStripeFailure(error);

        assertThat(service.attempts).singleElement().satisfies(this::assertEmailParams);
    }

    @Test
    void propagatesFallbackFailureWithoutAnotherAttempt() {
        givenCustomerId(CUSTOMER);
        StripeException retryError = invalidRequest("resource_missing", "customer");
        service.failures = List.of(invalidRequest("resource_missing", "customer"), retryError);

        expectStripeFailure(retryError);

        assertThat(service.attempts).hasSize(2);
        assertEmailParams(service.attempts.get(1));
    }

    @Test
    void returnsNotFoundWhenUserDoesNotExist() {
        when(userRepository.findById(USER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.createCheckoutSession(USER_ID, EMAIL))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                        ResponseStatusException.class, status -> {
                            assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(status.getReason()).isEqualTo("User not found");
                        }))
                .verify(TIMEOUT);

        assertThat(service.attempts).isEmpty();
    }

    @Test
    void returnsBadRequestWhenUserIdIsNull() {
        StepVerifier.create(service.createCheckoutSession(null, EMAIL))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)))
                .verify(TIMEOUT);

        verifyNoInteractions(userRepository);
        assertThat(service.attempts).isEmpty();
    }

    private void givenCustomerId(String customerId) {
        User user = User.builder().id(USER_ID).stripeCustomerId(customerId).build();
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
    }

    private void expectStripeFailure(StripeException expected) {
        StepVerifier.create(service.createCheckoutSession(USER_ID, EMAIL))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(expected))
                .verify(TIMEOUT);
    }

    private void assertEmailParams(SessionCreateParams params) {
        assertThat(params.getCustomer()).isNull();
        assertThat(params.getCustomerEmail()).isEqualTo(EMAIL);
    }

    private static InvalidRequestException invalidRequest(String code, String param) {
        return new InvalidRequestException("Checkout rejected", param, "req_example", code, 400, null);
    }

    private static class StubBillingService extends BillingService {
        private final List<SessionCreateParams> attempts = new ArrayList<>();
        private List<StripeException> failures = List.of();

        StubBillingService(UserRepository userRepository) {
            super(userRepository, null, null);
        }

        @Override
        protected Session createStripeSession(SessionCreateParams params) throws StripeException {
            int attempt = attempts.size();
            attempts.add(params);
            if (attempt < failures.size()) {
                throw failures.get(attempt);
            }
            Session session = new Session();
            session.setUrl(CHECKOUT_URL);
            return session;
        }
    }
}
