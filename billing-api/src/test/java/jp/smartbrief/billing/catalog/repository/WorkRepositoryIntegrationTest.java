package jp.smartbrief.billing.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

import jp.smartbrief.billing.catalog.domain.Work;
import reactor.test.StepVerifier;

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
class WorkRepositoryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int SHORT_WORK_ID = 95001;
    private static final int LONG_WORK_ID = 95002;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private WorkRepository workRepository;

    @BeforeAll
    static void createWorksTable(@Autowired DatabaseClient databaseClient) {
        databaseClient.sql("CREATE DATABASE IF NOT EXISTS aozora_db").then()
                .then(databaseClient.sql("CREATE TABLE IF NOT EXISTS aozora_db.works LIKE works").then())
                .block(TIMEOUT);
    }

    @BeforeEach
    void insertWorks() {
        // The short original has more bytes and a longer translation than the long original.
        execute("""
                INSERT INTO aozora_db.works (work_id, title, summary_300, category, body_text, full_text)
                VALUES (95001, 'Pruning short', 'Short summary', 'pruning_test', 'Longer translation', '短文'),
                       (95002, 'Pruning long', 'Long summary', 'pruning_test', 'Body', 'abcd')
                """);
        execute("""
                INSERT INTO works (work_id, title, summary_300, category, body_text, full_text)
                SELECT work_id, title, summary_300, category, body_text, full_text
                FROM aozora_db.works WHERE work_id IN (95001, 95002)
                """);
    }

    @AfterEach
    void deleteWorks() {
        execute("DELETE FROM works WHERE work_id IN (95001, 95002)");
        execute("DELETE FROM aozora_db.works WHERE work_id IN (95001, 95002)");
    }

    @Test
    void keywordSearchReturnsDisplayFieldsWithoutBodyText() {
        var works = workRepository.searchByKeyword("Pruning short", 10, 0).collectList().block(TIMEOUT);

        assertThat(works).singleElement().satisfies(work -> {
            assertThat(work.getId()).isEqualTo(SHORT_WORK_ID);
            assertThat(work.getTitle()).isEqualTo("Pruning short");
            assertThat(work.getSummary300()).isEqualTo("Short summary");
            assertThat(work.getBodyText()).isNull();
        });
    }

    @Test
    void categoryListReturnsDisplayFieldsWithoutBodyText() {
        var works = workRepository.findByCategory("pruning_test", 1, 0).collectList().block(TIMEOUT);

        assertThat(works).singleElement().satisfies(work -> {
            assertThat(work.getId()).isEqualTo(LONG_WORK_ID);
            assertThat(work.getTitle()).isEqualTo("Pruning long");
            assertThat(work.getSummary300()).isEqualTo("Long summary");
            assertThat(work.getBodyText()).isNull();
        });
    }

    @Test
    void bulkLookupReturnsWorksWithoutBodyText() {
        var works = workRepository.findAllByIdIn(List.of(SHORT_WORK_ID, LONG_WORK_ID))
                .collectList().block(TIMEOUT);

        assertThat(works).extracting(Work::getId).containsExactlyInAnyOrder(SHORT_WORK_ID, LONG_WORK_ID);
        assertThat(works).allSatisfy(work -> assertThat(work.getBodyText()).isNull());
    }

    @Test
    void bulkLookupWithNoIdsCompletesEmpty() {
        StepVerifier.create(workRepository.findAllByIdIn(List.of()))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void detailLookupReturnsBodyText() {
        var work = workRepository.findById(SHORT_WORK_ID).block(TIMEOUT);

        assertThat(work).isNotNull();
        assertThat(work.getBodyText()).isEqualTo("Longer translation");
    }

    @Test
    void lengthSearchSortsByOriginalTextCharacterCount() {
        var works = workRepository.searchByKeywordOrderByLength("Pruning %", 10, 0)
                .collectList().block(TIMEOUT);

        assertThat(works).extracting(Work::getId).containsExactly(LONG_WORK_ID, SHORT_WORK_ID);
    }

    private void execute(String sql) {
        databaseClient.sql(sql).fetch().rowsUpdated().block(TIMEOUT);
    }
}
