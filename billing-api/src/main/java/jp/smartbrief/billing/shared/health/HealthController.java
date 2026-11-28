package jp.smartbrief.billing.shared.health;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@Slf4j
public class HealthController {

    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(2500);

    private final R2dbcEntityTemplate primaryDatabase;

    public HealthController(@Qualifier("primaryEntityTemplate") R2dbcEntityTemplate primaryDatabase) {
        this.primaryDatabase = primaryDatabase;
    }

    @GetMapping(value = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> health() {
        return Mono.defer(() -> primaryDatabase.getDatabaseClient().sql("SELECT 1").fetch().one())
                .switchIfEmpty(Mono.error(new IllegalStateException("Database health query returned no row")))
                .timeout(PROBE_TIMEOUT)
                .map(row -> response(HttpStatus.OK, "ok"))
                .onErrorResume(error -> {
                    log.warn("Database health check failed", error);
                    return Mono.just(response(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));
                });
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String bodyStatus) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("status", bodyStatus));
    }
}
