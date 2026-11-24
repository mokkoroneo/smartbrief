package jp.smartbrief.billing.catalog.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import jp.smartbrief.billing.catalog.domain.UserBookHistory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserBookHistoryRepository extends R2dbcRepository<UserBookHistory, Long> {

    @Query("SELECT * FROM user_book_history WHERE user_id = :userId ORDER BY viewed_at DESC LIMIT 100")
    Flux<UserBookHistory> findHistoryByUserId(Long userId);

    @Query("SELECT book_id FROM user_book_history WHERE book_id IS NOT NULL GROUP BY book_id ORDER BY COUNT(book_id) DESC LIMIT 10")
    Flux<Integer> findTopBookIds();

    Mono<UserBookHistory> findFirstByUserIdAndBookIdOrderByViewedAtDesc(Long userId, Integer bookId);
}
