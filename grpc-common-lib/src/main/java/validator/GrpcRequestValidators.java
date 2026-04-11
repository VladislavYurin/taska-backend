package validator;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Утилитный класс для валидации полей входящих gRPC-запросов.
 *
 * <p>При нарушении валидации возвращает {@link Mono#error} с gRPC-статусом
 * {@code INVALID_ARGUMENT}.</p>
 */
public final class GrpcRequestValidators {

    private GrpcRequestValidators() {
    }

    /**
     * Парсит строку как {@link UUID}.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с распарсенным {@link UUID} или ошибкой {@code INVALID_ARGUMENT}
     *         если значение пустое или не является валидным UUID
     */
    public static Mono<UUID> parseUuidOrInvalidArgument(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must not be blank")
                    .asRuntimeException());
        }
        try {
            return Mono.just(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be a valid UUID")
                    .asRuntimeException());
        }
    }

    /**
     * Проверяет что строка не пустая и не состоит из пробелов.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} со значением или ошибкой {@code INVALID_ARGUMENT}
     *         если значение пустое
     */
    public static Mono<String> requireNonBlankOrInvalidArgument(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must not be blank")
                    .asRuntimeException());
        }
        return Mono.just(raw);
    }
}