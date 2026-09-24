package nullable;

import io.grpc.Status;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.NullableDouble;
import ru.taska.api.common.v1.NullableInt32;
import ru.taska.api.common.v1.NullableString;
import validator.GrpcRequestValidators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Превращает proto-обёртки {@link NullableString}, {@link NullableDouble}, {@link NullableInt32}
 * в {@link NullableField}. Общая логика для всех методов этого класса:
 * <ul>
 *     <li>поле отсутствует в запросе → {@link NullableField#absent()};</li>
 *     <li>поле присутствует, {@code is_null == true} → {@link NullableField} со значением {@code null};</li>
 *     <li>поле присутствует, {@code is_null == false} → {@link NullableField} со значением из обёртки.</li>
 * </ul>
 */
public final class NullableFieldParsers {

    private NullableFieldParsers() {
    }

    /**
     * Превращает proto-тип {@link NullableString} в {@link NullableField} со значением {@link String}
     * Значение копируется как есть, без проверок формата
     */
    public static Mono<NullableField<String>> parseNullableString(boolean present, NullableString wrapper) {
        if (!present) {
            return Mono.just(NullableField.absent());
        }
        if (wrapper.getIsNull()) {
            return Mono.just(NullableField.of(null));
        }
        return Mono.just(NullableField.of(wrapper.getValue()));
    }

    /**
     * Превращает proto-тип {@link NullableString} в {@link NullableField} со значением {@link UUID}
     * Значение проверяется и парсится как UUID
     */
    public static Mono<NullableField<UUID>> parseNullableUuid(boolean present, NullableString wrapper, String fieldName) {
        if (!present) {
            return Mono.just(NullableField.absent());
        }
        if (wrapper.getIsNull()) {
            return Mono.just(NullableField.of(null));
        }
        return GrpcRequestValidators.parseUuidOrInvalidArgument(wrapper.getValue(), fieldName)
                .map(NullableField::of);
    }

    /**
     * Превращает proto-тип {@link NullableString} в {@link NullableField} со значением {@link LocalDate}
     * Значение парсится как ISO-дата (yyyy-MM-dd)
     */
    public static Mono<NullableField<LocalDate>> parseNullableDate(boolean present, NullableString wrapper, String fieldName) {
        if (!present) {
            return Mono.just(NullableField.absent());
        }
        if (wrapper.getIsNull()) {
            return Mono.just(NullableField.of(null));
        }
        return GrpcRequestValidators.parseDateOrInvalidArgument(wrapper.getValue(), fieldName)
                .map(NullableField::of);
    }

    /**
     * Превращает proto-тип {@link NullableDouble} в {@link NullableField} со значением {@link BigDecimal}
     * Значение конвертируется без проверки диапазона; NaN и бесконечность отклоняются
     */
    public static Mono<NullableField<BigDecimal>> parseNullableBigDecimal(boolean present, NullableDouble wrapper, String fieldName) {
        if (!present) {
            return Mono.just(NullableField.absent());
        }
        if (wrapper.getIsNull()) {
            return Mono.just(NullableField.of(null));
        }
        if (!Double.isFinite(wrapper.getValue())) {
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be a finite number")
                    .asRuntimeException());
        }
        return Mono.just(NullableField.of(BigDecimal.valueOf(wrapper.getValue())));
    }

    /**
     * Превращает proto-тип {@link NullableDouble} в {@link NullableField} со значением {@link BigDecimal}
     * Дополнительно проверяет, что значение не отрицательное
     */
    public static Mono<NullableField<BigDecimal>> parseNullableNonNegativeBigDecimal(boolean present, NullableDouble wrapper, String fieldName) {
        return parseNullableBigDecimal(present, wrapper, fieldName)
                .flatMap(field -> {
                    if (field.present() && field.value() != null && field.value().signum() < 0) {
                        return Mono.error(Status.INVALID_ARGUMENT
                                .withDescription(fieldName + " must be positive or zero")
                                .asRuntimeException());
                    }
                    return Mono.just(field);
                });
    }

    /**
     * Превращает proto-тип {@link NullableInt32} в {@link NullableField} со значением {@link Integer}
     * Значение копируется как есть, без проверки диапазона
     */
    public static Mono<NullableField<Integer>> parseNullableInt32(boolean present, NullableInt32 wrapper) {
        if (!present) {
            return Mono.just(NullableField.absent());
        }
        if (wrapper.getIsNull()) {
            return Mono.just(NullableField.of(null));
        }
        return Mono.just(NullableField.of(wrapper.getValue()));
    }

    /**
     * Превращает proto-тип {@link NullableInt32} в {@link NullableField} со значением {@link Integer}
     * Дополнительно проверяет, что значение не отрицательное
     */
    public static Mono<NullableField<Integer>> parseNullableNonNegativeInt32(boolean present, NullableInt32 wrapper, String fieldName) {
        return parseNullableInt32(present, wrapper)
                .flatMap(field -> {
                    if (field.present() && field.value() != null && field.value() < 0) {
                        return Mono.error(Status.INVALID_ARGUMENT
                                .withDescription(fieldName + " must be positive or zero")
                                .asRuntimeException());
                    }
                    return Mono.just(field);
                });
    }
}
