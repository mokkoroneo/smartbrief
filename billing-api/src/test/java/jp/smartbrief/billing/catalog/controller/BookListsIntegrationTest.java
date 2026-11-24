package jp.smartbrief.billing.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

import jp.smartbrief.billing.catalog.dto.BookResponse;
import jp.smartbrief.billing.identity.domain.User;

@SpringBootTest
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
class BookListsIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final User USER = User.builder().id(94001L).planType(User.Plan.FREE.name()).build();

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private BookController bookController;

    @BeforeAll
    static void createWorksTable(@Autowired DatabaseClient databaseClient) {
        databaseClient.sql("CREATE DATABASE IF NOT EXISTS aozora_db").then()
                .then(databaseClient.sql("CREATE TABLE IF NOT EXISTS aozora_db.works LIKE works").then())
                .block(TIMEOUT);
    }

    @BeforeEach
    void insertWorks() {
        execute("""
                INSERT INTO aozora_db.works (work_id, title)
                VALUES (94001, 'One'), (94002, 'Two'), (94003, 'Three')
                """);
    }

    @AfterEach
    void deleteFixtures() {
        execute("DELETE FROM user_book_history WHERE user_id = 94001");
        execute("DELETE FROM user_favorites WHERE user_id = 94001");
        execute("DELETE FROM aozora_db.works WHERE work_id IN (94001, 94002, 94003)");
    }

    @Test
    void historySortsByViewedAtAndPreservesRepeatedBooksWhileSkippingMissingWorks() {
        execute("""
                INSERT INTO user_book_history (user_id, book_id, viewed_at) VALUES
                    (94001, 94003, '2026-01-01 00:00:00'),
                    (94001, 94001, '2026-01-05 00:00:00'),
                    (94001, 94002, '2026-01-03 00:00:00'),
                    (94001, 94004, '2026-01-04 00:00:00'),
                    (94001, 94001, '2026-01-02 00:00:00')
                """);

        var response = bookController.getHistory(USER).block(TIMEOUT);

        assertThat(response.getBody()).extracting(BookResponse::getId)
                .containsExactly(94001, 94002, 94001, 94003);
    }

    @Test
    void favoritesPreserveDescendingCreationOrder() {
        execute("""
                INSERT INTO user_favorites (user_id, book_id, created_at) VALUES
                    (94001, 94001, '2026-01-01 00:00:00'),
                    (94001, 94002, '2026-01-03 00:00:00'),
                    (94001, 94003, '2026-01-02 00:00:00')
                """);

        var response = bookController.getFavorites(USER).block(TIMEOUT);

        assertThat(response.getBody()).extracting(BookResponse::getId)
                .containsExactly(94002, 94003, 94001);
    }

    private void execute(String sql) {
        databaseClient.sql(sql).fetch().rowsUpdated().block(TIMEOUT);
    }
}
