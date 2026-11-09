package ru.taska.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcErrorDetails;
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

  @org.springframework.beans.factory.annotation.Value("${taska.rpc.context.enabled:false}")
  private boolean trustedEnabled;

  public Mono<Void> handleError(ServerWebExchange exchange, Throwable error, String requestId) {
    HttpStatus httpStatus;
    String code;
    Status.Code transportCode = null;
    String message;
    Map<String, String> details = Map.of();

    // grpc ошибка пришедшая из другого сервиса
    if (error instanceof StatusRuntimeException grpcEx) {
      Status grpcStatus = grpcEx.getStatus();
      details = GrpcErrorDetails.decode(grpcEx.getTrailers());
      transportCode = grpcStatus.getCode();
      code = grpcStatus.getCode().name();
      message = grpcStatus.getDescription() != null ? grpcStatus.getDescription() : code;
      httpStatus = restErrorMapper.mapGrpcCodeToHttpStatus(grpcStatus.getCode());
      log.warn("[{}] gRPC error: {} - {}", requestId, code, message);
      // The shared validators also run in the gateway before making a gRPC call.
    } else if (error instanceof ru.taska.exception.DomainException domainEx) {
      code = domainEx.getStatus().name();
      transportCode = Status.Code.valueOf(code);
      details = domainEx.getDetails();
      message = domainEx.getMessage();
      httpStatus = restErrorMapper.mapGrpcCodeToHttpStatus(Status.Code.valueOf(code));
      log.warn("[{}] Validation error: {} - {}", requestId, code, message);
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

    String reason = details.getOrDefault("code", details.get("reason"));
    if (reason != null && !reason.matches("[A-Z][A-Z0-9_]{1,79}")) reason = null;
    if (trustedEnabled) {
      if (reason != null) code = reason;
      else if (transportCode != null) code = restErrorMapper.problemCode(transportCode);
      else if (httpStatus == HttpStatus.UNAUTHORIZED) code = "AUTHENTICATION_REQUIRED";
      else if (httpStatus == HttpStatus.FORBIDDEN) code = "SCOPE_DENIED";
      if (transportCode != null) httpStatus = restErrorMapper.problemStatus(transportCode, code);
    }
    RestErrorResponse errorResponse = new RestErrorResponse(code, message);
    errorResponse.setRequestId(requestId);
    String restriction = details.get("reason");
    if (restriction != null && !restriction.matches("[A-Z][A-Z0-9_]{1,79}")) restriction = null;
    errorResponse.setReasons(
        restriction == null ? java.util.List.of() : java.util.List.of(restriction));
    if (trustedEnabled) {
      errorResponse.setStatus(httpStatus.value());
      errorResponse.setTitle(httpStatus.getReasonPhrase());
    }
    errorResponse.setDetails(details.isEmpty() ? null : details);

    var response = exchange.getResponse();
    response.setStatusCode(httpStatus);
    response
        .getHeaders()
        .setContentType(
            trustedEnabled ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
    response.getHeaders().set(REQUEST_ID, requestId);
    String retryAfter = details.get("retryAfterSeconds");
    if (httpStatus == HttpStatus.TOO_MANY_REQUESTS
        && retryAfter != null
        && retryAfter.matches("[1-9][0-9]{0,5}")) {
      response.getHeaders().set(org.springframework.http.HttpHeaders.RETRY_AFTER, retryAfter);
    }

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
