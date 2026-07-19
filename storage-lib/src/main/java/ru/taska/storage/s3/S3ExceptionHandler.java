package ru.taska.storage.s3;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.function.Function;

/**
 * Централизованный обработчик ошибок S3/MinIO.
 */
@Slf4j
public final class S3ExceptionHandler {

    private S3ExceptionHandler() {
    }

    public static <T> Function<Mono<T>, Mono<T>> withErrorHandling(String operation) {
        return mono -> mono
                .onErrorMap(NoSuchKeyException.class, e -> {
                    log.warn("{}: object not found", operation);
                    return new DomainException(DomainStatus.NOT_FOUND, e.getMessage());
                })
                .onErrorMap(S3Exception.class, e -> {
                    if (e.statusCode() == 403) {
                        log.error("{}: access denied: {}", operation, e.getMessage());
                        return new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied to storage object");
                    }
                    log.error("{}: S3 error: {}", operation, e.getMessage());
                    return new DomainException(DomainStatus.INTERNAL, "Storage error");
                })
                .onErrorMap(SdkClientException.class, e -> {
                    log.error("{}: storage unavailable: {}", operation, e.getMessage());
                    return new DomainException(DomainStatus.UNAVAILABLE, "Storage service unavailable");
                })
                .onErrorMap(e -> !(e instanceof DomainException), e -> {
                    log.error("{}: unexpected error: {}", operation, e.getMessage());
                    return new DomainException(DomainStatus.UNKNOWN, "Unexpected storage error");
                });
    }
}
