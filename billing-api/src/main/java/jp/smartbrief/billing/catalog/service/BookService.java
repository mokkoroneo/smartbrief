package jp.smartbrief.billing.catalog.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import jp.smartbrief.billing.catalog.domain.UserBookHistory;
import jp.smartbrief.billing.catalog.domain.Work;
import jp.smartbrief.billing.catalog.dto.BookResponse;
import jp.smartbrief.billing.catalog.repository.UserBookHistoryRepository;
import jp.smartbrief.billing.catalog.repository.WorkRepository;
import jp.smartbrief.billing.shared.dto.UserContext;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BookService {

    private static final int TRANSLATION_PREVIEW_PARAGRAPHS = 4;
    private static final int TRANSLATION_PREVIEW_LENGTH = 500;
    private static final int HQ_PREVIEW_LINES = 10;

    private static final String CONSUME_DAILY_QUOTA_SQL = """
            UPDATE users
            SET daily_view_count = CASE WHEN last_view_date = :today THEN daily_view_count + 1 ELSE 1 END,
                last_view_date   = :today
            WHERE id = :userId
              AND (last_view_date IS NULL OR last_view_date <> :today OR daily_view_count < :limit)
            """;

    private final WorkRepository workRepository;
    private final UserBookHistoryRepository historyRepository;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    public Mono<BookResponse> getBookDetailWithLimit(Integer workId, UserContext context, int dailyLimit) {
        if (workId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Book ID is required"));
        }
        if (!context.isAuthenticated()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ログインが必要です"));
        }

        Long userId = Objects.requireNonNull(context.userId(), "User ID is required");

        return workRepository.findById(workId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "作品が見つかりません")))
                .flatMap(work -> processViewAndCreateResponse(work, userId, context.isPremium(), dailyLimit));
    }

    private Mono<BookResponse> processViewAndCreateResponse(
            Work work, Long userId, boolean isPremium, int dailyLimit) {
        BookResponse response = createResponse(work, isPremium);
        Integer bookId = work.getId();
        if (response.isLocked() || bookId == null) {
            return Mono.just(response);
        }

        LocalDateTime now = LocalDateTime.now();

        return hasRecentHistoryWithin60Seconds(userId, bookId, now)
                .flatMap(hasRecent -> {
                    if (hasRecent) {
                        return Mono.just(response);
                    }

                    if (isPremium) {
                        return insertHistory(work, userId, now)
                                .thenReturn(response);
                    }

                    LocalDate today = now.toLocalDate();
                    return consumeDailyQuotaAndInsertHistory(work, userId, today, now, dailyLimit)
                            .thenReturn(response);
                });
    }

    private Mono<Boolean> hasRecentHistoryWithin60Seconds(Long userId, Integer bookId, LocalDateTime now) {
        LocalDateTime threshold = now.minusSeconds(60);
        return historyRepository.findFirstByUserIdAndBookIdOrderByViewedAtDesc(userId, bookId)
                .filter(latest -> latest.getViewedAt() != null && latest.getViewedAt().isAfter(threshold))
                .hasElement();
    }

    private Mono<Void> consumeDailyQuotaAndInsertHistory(
            Work work, Long userId, LocalDate today, LocalDateTime viewedAt, int dailyLimit) {
        if (dailyLimit <= 0) {
            return Mono.error(dailyLimitExceededException(dailyLimit));
        }

        return databaseClient.sql(CONSUME_DAILY_QUOTA_SQL)
                .bind("today", today)
                .bind("userId", userId)
                .bind("limit", dailyLimit)
                .fetch()
                .rowsUpdated()
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        return Mono.error(dailyLimitExceededException(dailyLimit));
                    }
                    return insertHistory(work, userId, viewedAt);
                })
                .as(transactionalOperator::transactional);
    }

    private Mono<Void> insertHistory(Work work, Long userId, LocalDateTime viewedAt) {
        UserBookHistory history = new UserBookHistory();
        history.setUserId(userId);
        history.setBookId(work.getId());
        history.setBookTitle(work.getTitle());
        history.setAuthorName(work.getAuthorName());
        history.setViewedAt(viewedAt);
        return historyRepository.save(history).then();
    }

    private ResponseStatusException dailyLimitExceededException(int limit) {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "無料プランの1日の閲覧制限（" + limit + "回）に達しました。");
    }

    private BookResponse createResponse(Work work, boolean isPremium) {
        BookResponse response = BookResponse.from(work, isPremium);
        if (response != null) {
            String body = response.detailBodyText(work, isPremium);
            if (response.isTranslationRestricted()) {
                body = translationPreview(body);
            } else if (response.isLocked()) {
                body = hqPreview(body);
            }
            response.setBodyText(body);
        }
        return response;
    }

    private static String translationPreview(String body) {
        if (body == null) {
            return null;
        }

        List<String> paragraphs = normalizeNewlines(body).lines()
                .filter(line -> !line.strip().isEmpty())
                .limit(TRANSLATION_PREVIEW_PARAGRAPHS + 1L)
                .toList();
        String candidate = String.join("\n", paragraphs.subList(0,
                Math.min(paragraphs.size(), TRANSLATION_PREVIEW_PARAGRAPHS)));
        int length = candidate.length();
        if (paragraphs.size() <= TRANSLATION_PREVIEW_PARAGRAPHS && length <= TRANSLATION_PREVIEW_LENGTH) {
            return length < 2 ? null : truncateWithEllipsis(candidate, length / 2);
        }
        return length <= TRANSLATION_PREVIEW_LENGTH
                ? candidate
                : truncateWithEllipsis(candidate, TRANSLATION_PREVIEW_LENGTH - 1);
    }

    private static String normalizeNewlines(String body) {
        return body.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String hqPreview(String body) {
        if (body == null) {
            return null;
        }

        String[] lines = normalizeNewlines(body).split("\n", -1);
        return String.join("\n", Arrays.copyOf(lines, Math.min(lines.length, HQ_PREVIEW_LINES)));
    }

    private static String truncateWithEllipsis(String text, int end) {
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
