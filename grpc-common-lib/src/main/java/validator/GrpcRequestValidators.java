package validator;

import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import reactor.core.publisher.Mono;
import ru.taska.domain.UpdateField;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Утилитный класс для валидации полей входящих gRPC-запросов.
 *
 * <p>При нарушении валидации возвращает {@link Mono#error} с gRPC-статусом
 * {@code INVALID_ARGUMENT}.</p>
 */
public final class GrpcRequestValidators {

    private static final int MAX_LEN = 255;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    private GrpcRequestValidators() {
    }

    /**
     * Парсит строку как {@link UUID}.
     *
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с распарсенным {@link UUID} или ошибкой {@code INVALID_ARGUMENT}
     * если значение пустое или не является валидным UUID
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
     * если значение пустое
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
     * если значение меньше или равно нулю
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
     * если передан 0 (UNSPECIFIED)
     */
    public static <T extends ProtocolMessageEnum> Mono<T> requireSpecifiedOrInvalidArgument(T value, String fieldName) {
        if (value.getNumber() == 0) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be specified")
                    .asRuntimeException());
        }
        return Mono.just(value);
    }

    /**
     * Валидация для Protobuf-обёрток (StringValue, DoubleValue, Int64Value).
     * Если hasField == true, но обертка пустая (default instance) — это команда на зануление (UpdateField.of(null)).
     */
    public static <V extends GeneratedMessage, T> Mono<UpdateField<T>> validateOptionalWrapper(
            boolean hasField,
            V wrapper,
            Function<V, T> extractor,
            String fieldName) {

        if (!hasField) {
            return Mono.just(UpdateField.undefined());
        }

        // Если пришел дефолтный инстанс обертки (Gateway прислал Signal "стереть")
        // Для StringValue это getValue().isEmpty(), для чисел - проверка полей через сериализацию
        if (wrapper == null || wrapper.equals(wrapper.getDefaultInstanceForType())) {
            return Mono.just(UpdateField.of(null));
        }

        try {
            T value = extractor.apply(wrapper);
            // Дополнительная бизнес-валидация для чисел (если передано отрицательное значение)
            if (value instanceof Number && ((Number) value).doubleValue() < 0) {
                return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription(fieldName + " must be >= 0")
                        .asRuntimeException());
            }
            return Mono.just(UpdateField.of(value));
        } catch (Exception ex) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Invalid wrapper data for field: " + fieldName)
                    .asRuntimeException());
        }
    }

    /**
     * Парсит опциональный энум.
     * Если hasField == true, но пришел UNSPECIFIED — это команда на зануление приоритета.
     */
    public static <E extends Enum<E>> Mono<UpdateField<E>> parseOptionalEnum(
            boolean hasField,
            E enumValue,
            E unspecifiedValue,
            String fieldName) {

        if (!hasField) {
            return Mono.just(UpdateField.undefined());
        }

        if (enumValue == null || enumValue == unspecifiedValue) {
            return Mono.just(UpdateField.of(null));
        }

        return Mono.just(UpdateField.of(enumValue));
    }

    /**
     * Парсит опциональный Timestamp.
     * Если hasField == true, но секунды и наносекунды равны 0 (default instance) — это команда на удаление даты.
     */
    public static Mono<UpdateField<java.time.Instant>> parseOptionalTimestamp(
            boolean hasField,
            com.google.protobuf.Timestamp timestamp,
            String fieldName) {

        if (!hasField) {
            return Mono.just(UpdateField.undefined());
        }

        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return Mono.just(UpdateField.of(null));
        }

        try {
            return Mono.just(UpdateField.of(java.time.Instant.ofEpochSecond(
                    timestamp.getSeconds(),
                    timestamp.getNanos()
            )));
        } catch (Exception ex) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " has invalid timestamp format")
                    .asRuntimeException());
        }
    }
    /**
     * Парсит строку из gRPC в Instant. Если строка пустая/отсутствует — возвращает empty.
     * */
    public static Mono<Optional<Instant>> parseOptionalInstant(boolean hasField, Timestamp rawDate, String fieldName) {
        if (!hasField) {
            return Mono.just(Optional.empty());
        }
        if (rawDate == null || (rawDate.getSeconds() == 0 && rawDate.getNanos() == 0)) {
            return Mono.just(Optional.ofNullable(java.time.Instant.MIN));
        }
        return Mono.just(Optional.of(java.time.Instant.ofEpochSecond(rawDate.getSeconds(), rawDate.getNanos())));
    }

    /**
     * Валидирует любые опциональные данные.
     */
    public static Mono<Void> validateDateRange(Instant startDate, Instant dueDate) {
        if (startDate != null && dueDate != null && startDate.isAfter(dueDate)) {
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription("Start date must be less than or equal to due date")
                    .asRuntimeException());
        }
        return Mono.empty();
    }

    /**
     * Валидирует любые опциональные числовые данные. Должен быть больше нуля
     */
    public static <T extends Number> Mono<Optional<T>> validateOptionalNumbers(boolean hasField, T rawValue, String fieldName) {
        if (!hasField) {
            return Mono.just(Optional.empty());
        }
        if (rawValue.doubleValue() < 0) {
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be >= 0")
                    .asRuntimeException());
        }
        return Mono.just(Optional.of(rawValue));
    }

    /**
     * Валидирует любые опциональные данные.
     */
    public static <T> Mono<Optional<T>> validateAnyOptional(boolean hasField, T raw, String fieldName) {
        if (!hasField) {
            return Mono.just(Optional.empty());
        }
        return Mono.just(Optional.of(raw));
    }

    /**
     * Парсит опциональный String в UUID. Допускает null.
     */
    public static Mono<Optional<UUID>> parseOptionalStringToUUID(boolean hasField, String raw, String fieldName) {
        if (!hasField || raw == null || raw.isBlank()) {
            return Mono.just(Optional.empty());
        }
        try {
            return Mono.just(Optional.of(UUID.fromString(raw)));
        } catch (IllegalArgumentException ex) {
            return Mono.error(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be a valid UUID")
                    .asRuntimeException());
        }
    }
}
