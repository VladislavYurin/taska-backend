package ru.taska.transport.grpc.logging;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import ru.taska.exception.DomainException;

import java.util.function.Consumer;

/**
 * Общие хелперы логирования ошибок для gRPC-слоя issue-service.
 */
@Slf4j
public final class GrpcIssueLogging {

    private GrpcIssueLogging() {
    }

    public static Consumer<Throwable> logValidationError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof StatusRuntimeException e
                    && e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                log.error("[{}][{}] {} validation error: {}",
                        requestId, nodeId, operation, e.getStatus().getDescription());
            }
        };
    }

    public static Consumer<Throwable> logOnError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof DomainException e) {
                log.error("[{}][{}] {} failed: status={}, message={}",
                        requestId, nodeId, operation, e.getStatus(), e.getMessage());
                return;
            }
            // StatusRuntimeException (валидация) уже логируется в logValidationError
            if (!(throwable instanceof StatusRuntimeException)) {
                log.error("[{}][{}] Unexpected error in {}", requestId, nodeId, operation, throwable);
            }
        };
    }
}
