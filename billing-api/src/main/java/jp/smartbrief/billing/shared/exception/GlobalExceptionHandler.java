package jp.smartbrief.billing.shared.exception;

import java.util.Map;
import java.util.Objects;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@Order(-2)
public class GlobalExceptionHandler extends AbstractErrorWebExceptionHandler {

    public GlobalExceptionHandler(ErrorAttributes errorAttributes, WebProperties webProperties,
                                  ApplicationContext applicationContext, ServerCodecConfigurer serverCodecConfigurer) {
        super(errorAttributes, webProperties.getResources(), applicationContext);
        this.setMessageWriters(serverCodecConfigurer.getWriters());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Map<String, Object> errorProperties = getErrorAttributes(request, ErrorAttributeOptions.defaults());

        int status = (int) errorProperties.getOrDefault("status", 500);
        String errorLabel = "Internal Server Error";
        String message = "Unexpected error occurred";

        Throwable errorObj = getError(request);
        if (errorObj instanceof ResponseStatusException rse) {
            status = rse.getStatusCode().value();
            if (rse.getStatusCode().is4xxClientError()) {
                HttpStatus resolved = HttpStatus.resolve(status);
                errorLabel = (resolved != null) ? resolved.getReasonPhrase() : "Client Error";
                String reason = rse.getReason();
                message = (reason != null && !reason.isBlank()) ? reason : errorLabel;
            }
        }

        if (errorObj != null) {
            if (status >= 500) {
                log.error("⚠ [GlobalErrorHandler] {} ({}) at {}", errorObj.getClass().getSimpleName(), status, request.path());
            } else {
                log.warn("⚠ [GlobalErrorHandler] {} ({}) at {}", errorObj.getClass().getSimpleName(), status, request.path());
            }
        }

        Map<String, Object> body = Map.of(
            "status", status,
            "error", errorLabel,
            "message", message,
            "path", request.path()
        );

        return ServerResponse.status(status)
            .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
            .body(BodyInserters.fromValue(Objects.requireNonNull(body)));
    }
}