package ru.taska.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import ru.taska.domain.dto.RestErrorResponse;
import ru.taska.filter.RequestIdProvider;

/**
 * Глобальный обработчик ошибок валидации REST-запросов API Gateway.
 * <p>
 * Обрабатывает ошибки, возникающие на уровне Spring WebFlux/OpenAPI validation,
 * до выполнения бизнес-логики контроллеров.
 */
@RestControllerAdvice
public class GatewayValidationExceptionHandler {

    private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String INVALID_REQUEST_PARAMETERS = "Invalid request parameters";
    private static final String REQUEST_ID = "X-Request-Id";

    private final RequestIdProvider requestIdProvider;

    public GatewayValidationExceptionHandler(RequestIdProvider requestIdProvider) {
        this.requestIdProvider = requestIdProvider;
    }

    /**
     * Преобразует ошибки валидации входящего REST-запроса в единый REST error response.
     *
     * @param error    ошибка валидации query parameters, path variables или request body
     * @param exchange текущий HTTP exchange
     * @return HTTP 400 с унифицированным телом ошибки и X-Request-Id header
     */
    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            WebExchangeBindException.class,
            ServerWebInputException.class
    })
    public ResponseEntity<RestErrorResponse> handleValidationException(
            Exception error,
            ServerWebExchange exchange
    ) {
        String requestId = requestIdProvider.resolve(exchange);

        RestErrorResponse response = new RestErrorResponse(
                INVALID_ARGUMENT,
                INVALID_REQUEST_PARAMETERS
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(REQUEST_ID, requestId)
                .body(response);
    }
}