package ru.taska.error;

import io.grpc.Status;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RestErrorMapper {

  public String problemCode(Status.Code code) {
    return switch (code) {
      case INVALID_ARGUMENT, OUT_OF_RANGE -> "VALIDATION_FAILED";
      case UNAUTHENTICATED -> "AUTHENTICATION_REQUIRED";
      case PERMISSION_DENIED -> "SCOPE_DENIED";
      case NOT_FOUND -> "RESOURCE_NOT_FOUND";
      case FAILED_PRECONDITION, ABORTED, ALREADY_EXISTS -> "INVALID_STATE";
      case UNAVAILABLE, DEADLINE_EXCEEDED -> "DEPENDENCY_UNAVAILABLE";
      case RESOURCE_EXHAUSTED -> "RATE_LIMITED";
      default -> code.name();
    };
  }

  /** Blueprint section 6 distinguishes several errors sharing one transport status. */
  public HttpStatus problemStatus(Status.Code transport, String code) {
    if (transport == Status.Code.DEADLINE_EXCEEDED && "DEPENDENCY_UNAVAILABLE".equals(code))
      return HttpStatus.SERVICE_UNAVAILABLE;
    if ("VERSION_CONFLICT".equals(code)) {
      if (transport == Status.Code.ABORTED) return HttpStatus.PRECONDITION_FAILED;
      if (transport == Status.Code.FAILED_PRECONDITION) return HttpStatus.PRECONDITION_REQUIRED;
    }
    if (transport == Status.Code.FAILED_PRECONDITION
        && java.util.Set.of(
                "QUOTA_EXCEEDED",
                "INVITATION_EXPIRED",
                "INVITATION_EMAIL_MISMATCH",
                "CONFIRMATION_REQUIRED",
                "CONFIRMATION_EXPIRED")
            .contains(code)) return HttpStatus.UNPROCESSABLE_ENTITY;
    if ((transport == Status.Code.ABORTED || transport == Status.Code.FAILED_PRECONDITION)
        && java.util.Set.of(
                "IDEMPOTENCY_CONFLICT",
                "INVALID_STATE",
                "LAST_ADMIN",
                "OWNER_MEMBERSHIP_PROTECTED",
                "FREE_ELIGIBILITY_USED",
                "PAYMENT_PENDING")
            .contains(code)) return HttpStatus.CONFLICT;
    return mapGrpcCodeToHttpStatus(transport);
  }

  public HttpStatus mapGrpcCodeToHttpStatus(Status.Code code) {
    return switch (code) {
      case INVALID_ARGUMENT, OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
      case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
      case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case ALREADY_EXISTS -> HttpStatus.CONFLICT;
      case FAILED_PRECONDITION -> HttpStatus.BAD_REQUEST;
      case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
      case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
      case ABORTED -> HttpStatus.CONFLICT;
      case UNIMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
      default -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }
}
