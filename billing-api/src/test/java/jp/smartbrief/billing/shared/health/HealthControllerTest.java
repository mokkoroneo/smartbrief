package jp.smartbrief.billing.shared.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;

import jp.smartbrief.billing.identity.repository.UserRepository;
import jp.smartbrief.billing.shared.config.SecurityConfig;
import jp.smartbrief.billing.shared.security.AuthenticationManager;
import jp.smartbrief.billing.shared.security.AuthRateLimitFilter;
import jp.smartbrief.billing.shared.security.JwtUtil;
import jp.smartbrief.billing.shared.security.SecurityContextRepository;
import jp.smartbrief.billing.shared.util.RequestLoggingFilter;
import reactor.core.publisher.Mono;

@SpringJUnitConfig(HealthControllerTest.WebConfig.class)
class HealthControllerTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import({HealthController.class, SecurityConfig.class, AuthenticationManager.class,
            SecurityContextRepository.class, AuthRateLimitFilter.class, RequestLoggingFilter.class})
    static class WebConfig {
    }

    @MockitoBean(name = "primaryEntityTemplate")
    private R2dbcEntityTemplate primaryDatabase;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;
    private DatabaseClient database;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(10)).build();
        database = mock(DatabaseClient.class);
        when(primaryDatabase.getDatabaseClient()).thenReturn(database);
    }

    @Test
    void anonymousGetRunsOneProbeAndReturnsOnlyStatus() {
        probeReturns(Mono.just(Map.of("1", 1)));

        client.get().uri("/healthz").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(String.class).isEqualTo("{\"status\":\"ok\"}");

        verify(database).sql("SELECT 1");
        verifyNoInteractions(jwtUtil, userRepository);
    }

    @Test
    void connectionFailureReturnsOnlyGenericStatus() {
        probeReturns(Mono.error(new IllegalStateException("private database connection details")));

        expectUnavailable();

        verify(database).sql("SELECT 1");
    }

    @Test
    void stalledProbeIsCancelledAndReturnsUnavailable() {
        AtomicBoolean cancelled = new AtomicBoolean();
        probeReturns(Mono.<Map<String, Object>>never().doOnCancel(() -> cancelled.set(true)));
        long started = System.nanoTime();

        expectUnavailable();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(cancelled).isTrue();
        verify(database).sql("SELECT 1");
    }

    @Test
    void anonymousPostAndOtherPathsStayProtected() {
        client.post().uri("/healthz").exchange().expectStatus().isUnauthorized();
        client.get().uri("/healthz/other").exchange().expectStatus().isUnauthorized();

        verifyNoInteractions(database);
    }

    private void expectUnavailable() {
        client.get().uri("/healthz").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(String.class).isEqualTo("{\"status\":\"unavailable\"}");
    }

    private void probeReturns(Mono<Map<String, Object>> result) {
        DatabaseClient.GenericExecuteSpec statement = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> rows = mock(FetchSpec.class);
        when(database.sql("SELECT 1")).thenReturn(statement);
        when(statement.fetch()).thenReturn(rows);
        when(rows.one()).thenReturn(result);
    }
}
