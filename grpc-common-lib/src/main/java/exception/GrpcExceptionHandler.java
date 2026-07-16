package exception;

import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcException;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.function.Function;

/**
 * Централизованный обработчик ошибок gRPC servers
 */
//@Slf4j
//public final class GrpcExceptionHandler {
//
//    private GrpcExceptionHandler() {
//    }
//
//    public static <T> Function<Mono<T>, Mono<T>> withErrorHandling(String operationName) {
//        return mono -> mono
//                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
//                            e -> {
//                                log.error("{}: database error {}", operationName, e.getMessage());
//                                return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
//                            })
//                .onErrorMap(e -> !(e instanceof DomainException) && !(e instanceof StatusRuntimeException),
//                            e -> {
//                                log.error("{}: unexpected error {}", operationName, e.getMessage());
//                                return new DomainException(DomainStatus.INTERNAL, "Internal error");
//                            })
//                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
//                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
//    }
//}
@Slf4j
public final class GrpcExceptionHandler {

    private GrpcExceptionHandler() {
    }

    public static <T> Function<Mono<T>, Mono<T>> withErrorHandling(String operationName) {
        return mono -> mono
                .doOnError(e -> log.error("{}: ERROR TYPE: {}", operationName, e.getClass().getName(), e))
                // 1. Database ошибки → UNAVAILABLE
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        e -> {
                            log.error("{}: database error {}", operationName, e.getMessage());
                            return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
                        })
                // 2. DomainException → правильный gRPC статус
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                // 3. Все остальное → INTERNAL
                .onErrorMap(e -> !(e instanceof StatusRuntimeException),
                        e -> {
                            log.error("{}: unhandled error type {}: {}", operationName, e.getClass().getName(), e.getMessage(), e);
                            return io.grpc.Status.INTERNAL.withDescription("Internal error").asRuntimeException();
                        });
    }
}