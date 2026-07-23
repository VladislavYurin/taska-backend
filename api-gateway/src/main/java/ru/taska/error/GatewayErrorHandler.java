package ru.taska.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.dto.RestErrorResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayErrorHandler {

    private final ObjectMapper objectMapper;
    private final RestErrorMapper restErrorMapper;

    private static final String REQUEST_ID = "X-Request-Id";

    public Mono<Void> handleError(ServerWebExchange exchange, Throwable error, String requestId) {
        HttpStatus httpStatus;
        String code;
        String message;

        // grpc ошибка пришедшая из другого сервиса
        if (error instanceof StatusRuntimeException grpcEx) {
            Status grpcStatus = grpcEx.getStatus();
            code = grpcStatus.getCode().name();
            message = grpcStatus.getDescription() != null ? grpcStatus.getDescription() : code;
            httpStatus = restErrorMapper.mapGrpcCodeToHttpStatus(grpcStatus.getCode());
            log.warn("[{}] gRPC error: {} - {}", requestId, code, message);
        // http ошибка из BearerTokenExtractor
        } else if (error instanceof ResponseStatusException rse) {
            httpStatus = HttpStatus.resolve(rse.getStatusCode().value());
            if (httpStatus == null) {
                httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            code = httpStatus.name();
            message = rse.getReason() != null ? rse.getReason() : httpStatus.getReasonPhrase();
            log.warn("[{}] Request error: {} - {}", requestId, code, message);
        // непредвиденная ошибка
        } else {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL";
            message = "Internal server error";
            log.error("[{}] Unexpected error: {}", requestId, error.getMessage());
        }

        RestErrorResponse errorResponse = new RestErrorResponse(code, message);

        var response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(REQUEST_ID, requestId);

        try {
            byte[] body = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        } catch (JacksonException e) {
            log.error("[{}] Failed to serialize error response: {}", requestId, e.getMessage());
            return Mono.error(e);
        }
    }
}
