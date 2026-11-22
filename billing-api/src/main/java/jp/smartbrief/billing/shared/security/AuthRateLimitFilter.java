package jp.smartbrief.billing.shared.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@Order(-150)
public class AuthRateLimitFilter implements WebFilter {

    private static final Set<String> PROTECTED_AUTH_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/resend-verification",
        "/api/v1/line/link"
    );

    private static final String ENDPOINT_GROUP_KEY = "AUTH_GROUP";
    private static final int CLEANUP_THRESHOLD = 1024;

    private record WindowCounter(long windowStartMillis, int count) {}

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupMillis = new AtomicLong(0L);

    @Value("${app.rate-limit.auth.max-requests:10}")
    private int maxRequestsPerWindow = 10;

    @Value("${app.rate-limit.auth.window-seconds:60}")
    private long windowSeconds = 60L;

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        String normalizedPath = normalizePath(request.getURI().getPath());
        if (!PROTECTED_AUTH_PATHS.contains(normalizedPath)) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        cleanupExpiredIfNeeded(now, windowMillis);

        String clientIp = extractClientIp(request);
        String bucketKey = clientIp + "|" + ENDPOINT_GROUP_KEY;

        WindowCounter updated = counters.compute(bucketKey, (key, current) -> {
            if (current == null || (now - current.windowStartMillis()) >= windowMillis) {
                return new WindowCounter(now, 1);
            }
            return new WindowCounter(current.windowStartMillis(), current.count() + 1);
        });

        if (updated.count() > maxRequestsPerWindow) {
            long remainingMillis = (updated.windowStartMillis() + windowMillis) - now;
            long retryAfterSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);

            log.warn("Rate limit exceeded: ip={}, path={}, count={}", clientIp, normalizedPath, updated.count());

            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

            String jsonBody = """
                    {"status":429,"error":"Too Many Requests","message":"リクエスト回数の上限に達しました。しばらく待ってから再試行してください。","path":"%s"}"""
                    .formatted(request.getURI().getPath());
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(Objects.requireNonNull(bytes));
            return response.writeWith(Mono.just(Objects.requireNonNull(buffer)));
        }

        return chain.filter(exchange);
    }

    String extractClientIp(ServerHttpRequest request) {
        String cfIp = request.getHeaders().getFirst("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) {
            return cfIp.trim();
        }

        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String firstHop = xff.split(",")[0].trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        if (rawPath.length() > 1 && rawPath.endsWith("/")) {
            return rawPath.substring(0, rawPath.length() - 1);
        }
        return rawPath;
    }

    private void cleanupExpiredIfNeeded(long now, long windowMillis) {
        if (counters.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long prev = lastCleanupMillis.get();
        if ((now - prev) >= windowMillis && lastCleanupMillis.compareAndSet(prev, now)) {
            counters.entrySet().removeIf(entry -> (now - entry.getValue().windowStartMillis()) >= windowMillis);
        }
    }
}