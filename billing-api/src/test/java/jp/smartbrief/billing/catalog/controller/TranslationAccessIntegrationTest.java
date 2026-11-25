package jp.smartbrief.billing.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import jp.smartbrief.billing.catalog.domain.Work;
import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.shared.security.JwtUtil;

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
    "STRIPE_WEBHOOK_SECRET=whsec_test_secret_for_integration_test"
})
class TranslationAccessIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int WORK_ID = 96001;
    private static final String TITLE = "Translation access fixture";
    private static final String SHORT = "冒頭の一文。【見出し】後半の一文。";
    private static final String LONG = "プレミアムの長い要約";
    private static final String INSIGHT = "プレミアムの考察";
    private static final String BODY = "一段落\r\n\r\n二段落\r三段落\n　\n四段落\n未公開の五段落";
    private static final String PREVIEW = "一段落\n二段落\n三段落\n四段落";

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private DatabaseClient databaseClient;
    @Autowired
    private R2dbcEntityTemplate entityTemplate;

    private User user;

    @BeforeAll
    static void createWorksTable(@Autowired DatabaseClient db) {
        db.sql("CREATE DATABASE IF NOT EXISTS aozora_db").then()
                .then(db.sql("CREATE TABLE IF NOT EXISTS aozora_db.works LIKE works").then())
                .block(TIMEOUT);
    }

    @BeforeEach
    void createFreeUser() {
        user = userRepository.save(User.builder()
                .username("translation_access_test")
                .email("translation_access_test@example.com")
                .password("unused-test-password")
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .isVerified(true)
                .build()).block(TIMEOUT);
    }

    @AfterEach
    void removeFixtures() {
        databaseClient.sql("DELETE FROM aozora_db.works WHERE work_id = :id")
                .bind("id", WORK_ID).then().block(TIMEOUT);
        if (user != null) {
            databaseClient.sql("DELETE FROM user_book_history WHERE user_id = :id")
                    .bind("id", user.getId()).then().block(TIMEOUT);
            userRepository.deleteById(user.getId()).block(TIMEOUT);
        }
    }

    @Test
    void freeDetailReturnsOnlyShortAndPreviewAndRecordsOneViewAcrossImmediateReload() {
        insert(fullTranslation());

        assertLockKeys(detail(), true)
                .jsonPath("$.summaryText").isEqualTo(SHORT)
                .jsonPath("$.hasDetailedSummary").isEqualTo(true)
                .jsonPath("$.highQuality").isEqualTo(true)
                .jsonPath("$.insight").doesNotExist()
                .jsonPath("$.summaryHq").doesNotExist()
                .jsonPath("$.bodyText").isEqualTo(PREVIEW);

        detail().jsonPath("$.bodyText").isEqualTo(PREVIEW);
        assertThat(databaseClient.sql("SELECT daily_view_count FROM users WHERE id = :id")
                .bind("id", user.getId())
                .map((row, metadata) -> row.get("daily_view_count", Integer.class)).one().block(TIMEOUT))
                .isEqualTo(1);
        assertThat(databaseClient.sql("""
                        SELECT COUNT(*) AS views FROM user_book_history
                        WHERE user_id = :user AND book_id = :work
                        """)
                .bind("user", user.getId()).bind("work", WORK_ID)
                .map((row, metadata) -> row.get("views", Long.class)).one().block(TIMEOUT))
                .isEqualTo(1L);
    }

    @Test
    void premiumDetailKeepsLongInsightAndOriginalBody() {
        insert(fullTranslation());
        user.setPlanType(User.Plan.PREMIUM.name());
        user.setSubscriptionExpiresAt(LocalDateTime.now().plusDays(1));
        user = userRepository.save(user).block(TIMEOUT);

        assertLockKeys(detail(), false)
                .jsonPath("$.summaryText").isEqualTo(LONG)
                .jsonPath("$.insight").isEqualTo(INSIGHT)
                .jsonPath("$.summaryHq").isEqualTo("HQ要約")
                .jsonPath("$.bodyText").isEqualTo(BODY);
    }

    @Test
    void freeSearchReturnsShortWithoutInsightOrBody() {
        insert(fullTranslation());

        webTestClient.get().uri("/api/v1/books/search?q={q}", TITLE)
                .cookie("authToken", jwtUtil.generateToken(user.getUsername()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].category").isEqualTo("Gutenberg")
                .jsonPath("$[0].summaryText").isEqualTo(SHORT)
                .jsonPath("$[0].translationRestricted").isEqualTo(true)
                .jsonPath("$[0].insight").doesNotExist()
                .jsonPath("$[0].bodyText").doesNotExist();
    }

    @Test
    void missingShortDoesNotFallBackToLong() {
        insert(translation().summaryLong(LONG).build());
        detail().jsonPath("$.summaryText").doesNotExist()
                .jsonPath("$.translationRestricted").isEqualTo(true);
    }

    @Test
    void hqOnlyTranslationDoesNotReturnHqWithFlagOff() {
        insert(translation().summaryHq("HQ要約").build());
        detail().jsonPath("$.summaryText").doesNotExist()
                .jsonPath("$.summaryHq").doesNotExist()
                .jsonPath("$.translationRestricted").isEqualTo(false)
                .jsonPath("$.hasDetailedSummary").isEqualTo(true);
    }

    @Test
    void blankLongAndShortUseEffectiveFallbackWithFlagOff() {
        insert(translation().summaryLong("　\n\t").summaryShort("　\t").summary300("代替要約").build());
        detail().jsonPath("$.summaryText").isEqualTo("代替要約")
                .jsonPath("$.translationRestricted").isEqualTo(false);
    }

    @Test
    void invalidLongShortAndFallbackAreTreatedAsMissing() {
        insert(translation().summaryLong("[object Object]").summaryShort("[object Object]")
                .summary300("　").build());
        detail().jsonPath("$.summaryText").doesNotExist()
                .jsonPath("$.translationRestricted").isEqualTo(false);
    }

    @Test
    void blankBodyIsNullWhenInsightAloneRestrictsTranslation() {
        insert(translation().insight(INSIGHT).bodyText("\r\n　\r\t").build());
        detail().jsonPath("$.bodyText").doesNotExist()
                .jsonPath("$.insight").doesNotExist()
                .jsonPath("$.translationRestricted").isEqualTo(true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("previewCases")
    void freeBodyOnlyTranslationUsesPreviewBoundaries(
            String scenario, String body, String expected, boolean restricted) {
        insert(translation().bodyText(body).build());
        WebTestClient.BodyContentSpec response = detail()
                .jsonPath("$.locked").isEqualTo(false)
                .jsonPath("$.translationRestricted").isEqualTo(restricted);
        if (expected == null) {
            response.jsonPath("$.bodyText").doesNotExist();
        } else {
            response.jsonPath("$.bodyText").isEqualTo(expected);
        }
    }

    private static Stream<Arguments> previewCases() {
        String exactly500 = "a".repeat(494) + "\nb\nc\nd";
        String fourLongParagraphs = "a".repeat(493) + "\nb\nc\ndiscarded";
        return Stream.of(
                Arguments.of("skip blank lines, normalize CRLF and CR, stop before fifth", BODY, PREVIEW, true),
                Arguments.of("five paragraphs with a 500-unit candidate", exactly500 + "\nhidden", exactly500, true),
                Arguments.of("four paragraphs over limit", fourLongParagraphs,
                        fourLongParagraphs.substring(0, 499) + "…", true),
                Arguments.of("two paragraphs with 1200 units", "a".repeat(600) + "\n" + "b".repeat(599),
                        "a".repeat(499) + "…", true),
                Arguments.of("normal limit avoids splitting surrogate pair", "a".repeat(498) + "😀hidden",
                        "a".repeat(498) + "…", true),
                Arguments.of("short reads half including line separator", "一二三\r\n四五六七", "一二三\n…", true),
                Arguments.of("exactly 500 units fits the short rule", exactly500, "a".repeat(250) + "…", true),
                Arguments.of("short rule avoids splitting surrogate pair", "😀x", "…", true),
                Arguments.of("one character has no preview", "訳", null, true),
                Arguments.of("only empty lines without insight or long", "\r\n　\r\t", null, false),
                Arguments.of("empty body", "", null, false),
                Arguments.of("absent body", null, null, false));
    }

    private Work fullTranslation() {
        return translation().category("Gutenberg").summaryShort(SHORT).summaryLong(LONG)
                .insight(INSIGHT).summaryHq("HQ要約").isHq(true).bodyText(BODY).build();
    }

    private Work.WorkBuilder translation() {
        return Work.builder().id(WORK_ID).title(TITLE).category("TRANSLATION");
    }

    private void insert(Work work) {
        entityTemplate.insert(work).block(TIMEOUT);
    }

    private WebTestClient.BodyContentSpec detail() {
        return webTestClient.get().uri("/api/v1/books/{id}", WORK_ID)
                .cookie("authToken", jwtUtil.generateToken(user.getUsername()))
                .exchange().expectStatus().isOk().expectBody();
    }

    private WebTestClient.BodyContentSpec assertLockKeys(WebTestClient.BodyContentSpec response, boolean restricted) {
        return response.consumeWith(result -> {
            String json = new String(result.getResponseBody(), StandardCharsets.UTF_8);
            assertThat(keyCount(json, "translationRestricted")).isEqualTo(1);
            assertThat(keyCount(json, "isTranslationRestricted")).isZero();
            assertThat(keyCount(json, "locked")).isEqualTo(1);
            assertThat(keyCount(json, "isLocked")).isZero();
        }).jsonPath("$.translationRestricted").isEqualTo(restricted)
                .jsonPath("$.locked").isEqualTo(false);
    }

    private long keyCount(String json, String name) {
        return Pattern.compile(Pattern.quote("\"" + name + "\"") + "\\s*:")
                .matcher(json).results().count();
    }
}
