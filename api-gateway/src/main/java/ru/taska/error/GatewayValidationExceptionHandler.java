package ru.taska.error;

import jakarta.validation.ConstraintViolationException;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.MissingRequestValueException;
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

    private static final Pattern TENANT_DETAIL_PATH = Pattern.compile("^/api/v1/tenants/[^/]+$");

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
        if (isTenantRegistryRoute(exchange)) {
            boolean missingVersion = exchange.getRequest().getMethod() == HttpMethod.PATCH
                    && error instanceof MissingRequestValueException missing
                    && "header".equals(missing.getLabel())
                    && HttpHeaders.IF_MATCH.equalsIgnoreCase(missing.getName());
            HttpStatus status = missingVersion
                    ? HttpStatus.PRECONDITION_REQUIRED
                    : HttpStatus.BAD_REQUEST;
            RestErrorResponse problem = new RestErrorResponse(
                    missingVersion ? "VERSION_CONFLICT" : "VALIDATION_FAILED",
                    missingVersion ? "If-Match header is required" : INVALID_REQUEST_PARAMETERS);
            problem.setStatus(status.value());
            problem.setTitle(status.getReasonPhrase());
            problem.setRequestId(requestId);
            return ResponseEntity.status(status)
                    .header(REQUEST_ID, requestId)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }

        RestErrorResponse response = new RestErrorResponse(
                INVALID_ARGUMENT,
                INVALID_REQUEST_PARAMETERS
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(REQUEST_ID, requestId)
                .body(response);
    }

    static boolean isTenantRegistryRoute(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        HttpMethod method = exchange.getRequest().getMethod();
        boolean detail = TENANT_DETAIL_PATH.matcher(path).matches();
        return ((method == HttpMethod.GET || method == HttpMethod.HEAD)
                && (detail || "/api/v1/tenants".equals(path)))
                || (method == HttpMethod.PATCH && detail);
    }
}