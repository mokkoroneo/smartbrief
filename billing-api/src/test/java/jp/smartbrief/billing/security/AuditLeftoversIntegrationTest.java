package jp.smartbrief.billing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import jp.smartbrief.billing.catalog.domain.Work;
import jp.smartbrief.billing.catalog.repository.WorkRepository;
import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.shared.security.JwtUtil;
import reactor.core.publisher.Mono;

@SpringBootTest
@AutoConfigureWebTestClient(timeout = "PT15S")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "SPRING_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "SPRING_R2DBC_USERNAME=root",
    "SPRING_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "AOZORA_R2DBC_URL=r2dbc:mysql://${TEST_MYSQL_HOST:mysql_server}:3306/smartbrief_test_db",
    "AOZORA_R2DBC_USERNAME=root",
    "AOZORA_R2DBC_PASSWORD=${TEST_MYSQL_PASSWORD}",
    "aozora.r2dbc.pool.initial-size=0",
    "JWT_SECRET_KEY=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLXB1cnBvc2Utb25seQ==",
    "JWT_EXPIRATION=3600000",
    "STRIPE_API_KEY=test-stripe-api-key-for-testing-only",
    "stripe.api.key=test-stripe-api-key-for-testing-only",
    "STRIPE_PRICE_ID=price_test_dummy_for_testing",
    "STRIPE_WEBHOOK_SECRET=whsec_test_secret_for_integration_test",
    "n8n.webhook.email=http://127.0.0.1:65534/dummy-email",
    "n8n.webhook.verify=http://127.0.0.1:65534/dummy-verify",
    "spring.sql.init.mode=always"
})
class AuditLeftoversIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private DatabaseClient databaseClient;

    @MockitoBean
    private WorkRepository workRepository;

    private User verifiedFreeUser;
    private User unverifiedUser;

    @BeforeEach
    void setUp() {
        databaseClient.sql("DELETE FROM user_book_history").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM processed_stripe_events").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();

        when(workRepository.findById(anyInt())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0);
            if (id != null && id == 2001) {
                return Mono.just(Work.builder()
                        .id(2001)
                        .title("上限検証作品")
                        .authorName("テスト作家")
                        .summary300("要約")
                        .bodyText("本文")
                        .build());
            }
            return Mono.empty();
        });

        verifiedFreeUser = userRepository.save(User.builder()
                .username("audit_free_user")
                .email("registered_verified@example.com")
                .password(passwordEncoder.encode("ValidPass123!"))
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .isVerified(true)
                .build()).block();

        unverifiedUser = userRepository.save(User.builder()
                .username("audit_unverified_user")
                .email("registered_unverified@example.com")
                .password(passwordEncoder.encode("ValidPass123!"))
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .isVerified(false)
                .verificationToken("initial-token")
                .build()).block();
    }

    @Test
    void defaultProfile_shouldNotEmitDebugLogsOnDbOperations(CapturedOutput output) {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .header("CF-Connecting-IP", "198.51.100.10")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "audit_free_user", "password", "ValidPass123!"))
                .exchange()
                .expectStatus().isOk();

        assertThat(output.getOut()).doesNotContain(" DEBUG ");
    }

    @Test
    void getMyInfo_shouldReturnFalseForExpiredPremium() {
        verifiedFreeUser.setPlanType(User.Plan.PREMIUM.name());
        verifiedFreeUser.setSubscriptionExpiresAt(LocalDateTime.now().minusDays(1));
        userRepository.save(verifiedFreeUser).block();

        assertMyInfo(verifiedFreeUser, false);
    }

    @Test
    void getMyInfo_shouldReturnTrueForActivePremium() {
        verifiedFreeUser.setPlanType(User.Plan.PREMIUM.name());
        verifiedFreeUser.setSubscriptionExpiresAt(LocalDateTime.now().plusDays(1));
        userRepository.save(verifiedFreeUser).block();

        assertMyInfo(verifiedFreeUser, true);
    }

    @Test
    void getMyInfo_shouldReturnFalseForFree() {
        assertMyInfo(verifiedFreeUser, false);
    }

    private void assertMyInfo(User user, boolean expectedPremium) {
        webTestClient.get()
                .uri("/api/v1/auth/me")
                .cookie("authToken", jwtUtil.generateToken(user.getUsername()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(user.getUsername())
                .jsonPath("$.email").isEqualTo(user.getEmail())
                .jsonPath("$.plan").isEqualTo(user.getPlanType())
                .jsonPath("$.isPremium").isBoolean()
                .jsonPath("$.isPremium").isEqualTo(expectedPremium);
    }

    @Test
    void globalExceptionHandler_shouldPassThrough403ReasonWhenDailyLimitExceeded() {
        databaseClient.sql("UPDATE users SET daily_view_count = 10, last_view_date = :today WHERE id = :uid")
                .bind("today", LocalDate.now())
                .bind("uid", verifiedFreeUser.getId())
                .fetch()
                .rowsUpdated()
                .block();

        String jwt = jwtUtil.generateToken(verifiedFreeUser.getUsername());

        webTestClient.get()
                .uri("/api/v1/books/2001")
                .cookie("authToken", jwt)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.error").isEqualTo("Forbidden")
                .jsonPath("$.message").value(msg -> assertThat((String) msg).contains("無料プランの1日の閲覧制限"));
    }

    @Test
    void cors_shouldBeUnifiedUnderSecurityConfigWithoutWildcardHeaders() {
        String targetUrl = "http://localhost/api/v1/books/ranking";

        // 1. SecurityConfig で明示許可されたヘッダー（Content-Type）は 200 OK
        webTestClient.options()
                .uri(targetUrl)
                .header(HttpHeaders.ORIGIN, "https://smartbrief.jp")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://smartbrief.jp")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers).doesNotContain("*").containsIgnoringCase("Content-Type"));

        // 2. WebConfig の allowedHeaders("*") が撤去されたため、未許可ヘッダーは 403 Forbidden で拒否される
        webTestClient.options()
                .uri(targetUrl)
                .header(HttpHeaders.ORIGIN, "https://smartbrief.jp")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Disallowed-Custom-Header")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void forgotPassword_shouldReturnIdenticalStatusAndBodyForRegisteredAndUnregistered() {
        EntityExchangeResult<String> registeredRes = webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .header("CF-Connecting-IP", "198.51.100.20")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", verifiedFreeUser.getEmail()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        EntityExchangeResult<String> unregisteredRes = webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .header("CF-Connecting-IP", "198.51.100.21")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "never_registered_xyz@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        assertThat(registeredRes.getResponseBody()).isEqualTo(unregisteredRes.getResponseBody());
    }

    @Test
    void resendVerification_shouldReturnIdenticalStatusAndBodyAcrossAllAccountStates() {
        EntityExchangeResult<String> unverifiedRes = webTestClient.post()
                .uri("/api/v1/auth/resend-verification")
                .header("CF-Connecting-IP", "198.51.100.30")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", unverifiedUser.getEmail()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        EntityExchangeResult<String> verifiedRes = webTestClient.post()
                .uri("/api/v1/auth/resend-verification")
                .header("CF-Connecting-IP", "198.51.100.31")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", verifiedFreeUser.getEmail()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        EntityExchangeResult<String> unregisteredRes = webTestClient.post()
                .uri("/api/v1/auth/resend-verification")
                .header("CF-Connecting-IP", "198.51.100.32")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "not_existing_user_999@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        assertThat(unverifiedRes.getResponseBody())
                .isEqualTo(verifiedRes.getResponseBody())
                .isEqualTo(unregisteredRes.getResponseBody());
    }

    @Test
    void rateLimit_shouldEnforce10PerMinuteByCfConnectingIpAndAllowNormalLoginFromOtherIp() {
        String attackerCfIp = "198.51.100.99";

        for (int i = 1; i <= 10; i++) {
            webTestClient.post()
                    .uri("/api/v1/auth/login")
                    .header("CF-Connecting-IP", attackerCfIp)
                    .header("X-Forwarded-For", "10.0.0." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", "audit_free_user", "password", "WrongPass!"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        webTestClient.post()
                .uri("/api/v1/auth/login")
                .header("CF-Connecting-IP", attackerCfIp)
                .header("X-Forwarded-For", "203.0.113.250")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "audit_free_user", "password", "WrongPass!"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");

        webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .header("CF-Connecting-IP", attackerCfIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "anyone@example.com"))
                .exchange()
                .expectStatus().isEqualTo(429);

        webTestClient.post()
                .uri("/api/v1/auth/login")
                .header("CF-Connecting-IP", "198.51.100.100")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "audit_free_user", "password", "ValidPass123!"))
                .exchange()
                .expectStatus().isOk();
    }
}