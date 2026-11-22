package jp.smartbrief.billing.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import jp.smartbrief.billing.catalog.domain.Work;
import jp.smartbrief.billing.catalog.dto.BookResponse;
import jp.smartbrief.billing.catalog.repository.UserBookHistoryRepository;
import jp.smartbrief.billing.catalog.repository.WorkRepository;
import jp.smartbrief.billing.identity.domain.User;
import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.shared.dto.UserContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@SpringBootTest
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
    "spring.sql.init.mode=always"
})
class BookServiceConcurrencyTest {

    @Autowired
    private BookService bookService;

    @MockitoBean
    private WorkRepository workRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private TransactionalOperator transactionalOperator;

    private User freeUser;
    private UserContext freeContext;

    @BeforeEach
    void setUp() {
        databaseClient.sql("DELETE FROM user_book_history").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM processed_stripe_events").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();

        when(workRepository.findById(anyInt())).thenAnswer(invocation -> {
            Integer id = invocation.getArgument(0);
            if (id != null && id >= 1001 && id <= 1025) {
                int idx = id - 1000;
                return Mono.just(Work.builder()
                        .id(id)
                        .title("テスト作品_" + idx)
                        .authorName("テスト著者")
                        .summary300("300字要約")
                        .summaryHq("HQ要約")
                        .isHq(true)
                        .bodyText("テスト本文_" + idx)
                        .build());
            }
            return Mono.empty();
        });

        freeUser = userRepository.save(User.builder()
                .username("free_concurrency_user")
                .email("free_concurrency@example.com")
                .password("hashed_pw")
                .roles("ROLE_USER")
                .planType(User.Plan.FREE.name())
                .isVerified(true)
                .build()).block();

        freeContext = UserContext.from(freeUser);
    }

    @Test
    void concurrentRequests_20ParallelWithLimit10_shouldCapCounterAndHistoryAt10() {
        int dailyLimit = 10;
        int totalConcurrentRequests = 20;
        Sinks.Empty<Void> startGate = Sinks.empty();

        Mono<List<Integer>> statusesMono = Flux.range(1, totalConcurrentRequests)
                .flatMap(i -> startGate.asMono()
                        .subscribeOn(Schedulers.parallel())
                        .then(bookService.getBookDetailWithLimit(1000 + i, freeContext, dailyLimit))
                        .map(res -> 200)
                        .onErrorResume(ResponseStatusException.class, ex -> Mono.just(ex.getStatusCode().value())),
                        totalConcurrentRequests)
                .collectList();

        Schedulers.parallel().schedule(() -> startGate.tryEmitEmpty());

        List<Integer> statuses = statusesMono.block();
        assertThat(statuses).hasSize(totalConcurrentRequests);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(dailyLimit);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.FORBIDDEN.value()).count())
                .isEqualTo(totalConcurrentRequests - dailyLimit);

        Long historyCount = databaseClient.sql("SELECT COUNT(*) FROM user_book_history WHERE user_id = :uid")
                .bind("uid", freeUser.getId())
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .block();
        assertThat(historyCount).isEqualTo(dailyLimit);

        Integer viewCount = databaseClient.sql("SELECT daily_view_count FROM users WHERE id = :uid")
                .bind("uid", freeUser.getId())
                .map((row, meta) -> row.get(0, Integer.class))
                .one()
                .block();
        assertThat(viewCount).isEqualTo(dailyLimit);
    }

    @Test
    void reloadWithin60SecondsAfterReachingLimit_shouldReturn200WithBody() {
        int dailyLimit = 10;

        for (int i = 1; i <= dailyLimit; i++) {
            BookResponse res = bookService.getBookDetailWithLimit(1000 + i, freeContext, dailyLimit).block();
            assertThat(res).isNotNull();
        }

        StepVerifier.create(bookService.getBookDetailWithLimit(1011, freeContext, dailyLimit))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        BookResponse reloadResponse = bookService.getBookDetailWithLimit(1010, freeContext, dailyLimit).block();
        assertThat(reloadResponse).isNotNull();
        assertThat(reloadResponse.getBodyText()).isEqualTo("テスト本文_10");

        Long historyCount = databaseClient.sql("SELECT COUNT(*) FROM user_book_history WHERE user_id = :uid")
                .bind("uid", freeUser.getId())
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .block();
        assertThat(historyCount).isEqualTo(dailyLimit);
    }

    @Test
    void previousDayLastViewDate_shouldResetCounterToOne() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        databaseClient.sql("UPDATE users SET daily_view_count = 10, last_view_date = :yesterday WHERE id = :uid")
                .bind("yesterday", yesterday)
                .bind("uid", freeUser.getId())
                .fetch()
                .rowsUpdated()
                .block();

        BookResponse response = bookService.getBookDetailWithLimit(1001, freeContext, 10).block();
        assertThat(response).isNotNull();
        assertThat(response.getBodyText()).isEqualTo("テスト本文_1");

        databaseClient.sql("SELECT daily_view_count, last_view_date FROM users WHERE id = :uid")
                .bind("uid", freeUser.getId())
                .map((row, meta) -> {
                    assertThat(row.get("daily_view_count", Integer.class)).isEqualTo(1);
                    assertThat(row.get("last_view_date", LocalDate.class)).isEqualTo(LocalDate.now());
                    return true;
                })
                .one()
                .block();
    }

    @Test
    void historyInsertFailure_shouldRollbackDailyViewCounter() {
        UserBookHistoryRepository failingHistoryRepo = mock(UserBookHistoryRepository.class);
        when(failingHistoryRepo.findFirstByUserIdAndBookIdOrderByViewedAtDesc(any(), any()))
                .thenReturn(Mono.empty());
        when(failingHistoryRepo.save(any()))
                .thenReturn(Mono.error(new RuntimeException("Simulated DB failure on history INSERT")));

        BookService serviceWithFailingHistory = new BookService(
                workRepository, failingHistoryRepo, databaseClient, transactionalOperator);

        StepVerifier.create(serviceWithFailingHistory.getBookDetailWithLimit(1001, freeContext, 10))
                .expectErrorMessage("Simulated DB failure on history INSERT")
                .verify();

        Integer viewCount = databaseClient.sql("SELECT daily_view_count FROM users WHERE id = :uid")
                .bind("uid", freeUser.getId())
                .map((row, meta) -> row.get(0, Integer.class))
                .one()
                .block();
        assertThat(viewCount).isZero();
    }
}