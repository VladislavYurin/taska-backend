package validator;

import com.google.protobuf.ProtocolMessageEnum;
import io.grpc.Status;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Утилитный класс для валидации полей входящих gRPC-запросов.
 *
 * <p>При нарушении валидации возвращает {@link Mono#error} с gRPC-статусом
 * {@code INVALID_ARGUMENT}.</p>
 */
public final class GrpcRequestValidators {

    private static final int MAX_LEN = 255;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    private static final String HEADER_REQUEST_ID = "header.requestId";
    private static final String HEADER_NODE_ID = "header.nodeId";

    private GrpcRequestValidators() {
    }

    /**
     * Проверяет {@code header.requestId}: не пустой.
     */
    public static Mono<String> requireHeaderRequestId(String raw) {
        return requireNonBlank(raw, HEADER_REQUEST_ID);
    }

    /**
     * Проверяет {@code header.nodeId}: не пустой.
     */
    public static Mono<String> requireHeaderNodeId(String raw) {
        return requireNonBlank(raw, HEADER_NODE_ID);
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
        return requireNonBlank(raw, fieldName)
                .flatMap(value -> {
                    try {
                        return Mono.just(UUID.fromString(value));
                    } catch (IllegalArgumentException ex) {
                        return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must be a valid UUID")
                                                  .asRuntimeException());
                    }
                });
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
        return requireNonBlank(raw, fieldName);
    }

    /**
     * Проверяет что логин не пустой и не содержит '@'.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} со значением или ошибкой {@code INVALID_ARGUMENT} при нарушении валидации
     *
     */
    public static Mono<String> requireValidLogin(String raw, String fieldName) {
        return requireNonBlank(raw, fieldName)
                .filter(login -> !login.contains("@"))
                .switchIfEmpty(Mono.error(io.grpc.Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must not contain '@'")
                                                  .asRuntimeException()));
    }

    /**
     * Проверяет ключ идемпотентности по длине и паттерну.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с ключом или ошибкой {@code INVALID_ARGUMENT} при нарушении валидации
     */
    public static Mono<String> validateIdempotencyKey(String raw, String fieldName) {
        return requireNonBlank(raw, fieldName)
                .filter(key -> key.length() <= MAX_LEN && KEY_PATTERN.matcher(key).matches())
                .switchIfEmpty(Mono.error(Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must be valid Idempotency Key")
                                                  .asRuntimeException()));
    }

    /**
     * Проверяет что email не пустой и содержит '@'.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} со значением или ошибкой {@code INVALID_ARGUMENT} при нарушении валидации
     *
     */
    public static Mono<String> requireValidEmail(String raw, String fieldName) {
        return requireNonBlank(raw, fieldName)
                .filter(email -> email.contains("@"))
                .switchIfEmpty(Mono.error(io.grpc.Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must contain '@'")
                                                  .asRuntimeException()));
    }

    private static Mono<String> requireNonBlank(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                                      .withDescription(fieldName + " must not be blank")
                                      .asRuntimeException());
        }
        return Mono.just(raw);
    }

    /**
     * Проверяет что числовое значение строго положительное (больше нуля).
     *
     * @param value     числовое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} со значением или ошибкой {@code INVALID_ARGUMENT}
     *         если значение меньше или равно нулю
     */
    public static Mono<Long> requirePositiveOrInvalidArgument(long value, String fieldName) {
        if (value <= 0) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be positive")
                    .asRuntimeException());
        }
        return Mono.just(value);
    }

    /**
     * Проверяет что proto enum задан (не является значением UNSPECIFIED, т.е. не равен 0).
     *
     * @param value     значение proto enum
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} со значением или ошибкой {@code INVALID_ARGUMENT}
     *         если передан 0 (UNSPECIFIED)
     */
    public static <T extends ProtocolMessageEnum> Mono<T> requireSpecifiedOrInvalidArgument(T value, String fieldName) {
        if (value.getNumber() == 0) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be specified")
                    .asRuntimeException());
        }
        return Mono.just(value);
    }
}