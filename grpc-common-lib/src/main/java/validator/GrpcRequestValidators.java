package validator;

import com.google.protobuf.ProtocolMessageEnum;
import io.grpc.Status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static final String HEADER = "header.";
    private static final String BODY = "body.";

    private static final String HEADER_REQUEST_ID = HEADER + "requestId";
    private static final String HEADER_NODE_ID = HEADER + "nodeId";

    private static final String BODY_ISSUE_ID = BODY + "issueId";
    private static final String BODY_ACTOR_USER_ID = BODY + "actorUserId";
    private static final String BODY_TARGET_USER_ID = BODY + "targetUserId";
    private static final String BODY_AUTHOR_USER_ID = BODY + "authorUserId";
    private static final String BODY_COMMENT_ID = BODY + "commentId";
    private static final String BODY_BODY = BODY + "body";

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
     * Парсит {@code body.issueId} как {@link UUID}.
     */
    public static Mono<UUID> parseBodyIssueId(String raw) {
        return parseUuidOrInvalidArgument(raw, BODY_ISSUE_ID);
    }

    /**
     * Парсит {@code body.actorUserId} как {@link UUID}.
     */
    public static Mono<UUID> parseBodyActorUserId(String raw) {
        return parseUuidOrInvalidArgument(raw, BODY_ACTOR_USER_ID);
    }

    /**
     * Парсит {@code body.targetUserId} как {@link UUID}.
     */
    public static Mono<UUID> parseBodyTargetUserId(String raw) {
        return parseUuidOrInvalidArgument(raw, BODY_TARGET_USER_ID);
    }

    /**
     * Парсит {@code body.authorUserId} как {@link UUID}.
     */
    public static Mono<UUID> parseBodyAuthorUserId(String raw) {
        return parseUuidOrInvalidArgument(raw, BODY_AUTHOR_USER_ID);
    }

    /**
     * Парсит {@code body.commentId} как {@link UUID}.
     */
    public static Mono<UUID> parseBodyCommentId(String raw) {
        return parseUuidOrInvalidArgument(raw, BODY_COMMENT_ID);
    }

    /**
     * Проверяет {@code body.body}: не пустой.
     */
    public static Mono<String> requireBodyBody(String raw) {
        return requireNonBlank(raw, BODY_BODY);
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
                        return Mono.error(Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must be a valid UUID")
                                                  .asRuntimeException());
                    }
                });
    }

    /**
     * Парсит опциональное строковое значение как {@link UUID}.
     *
     * <p>Если поле отсутствует, возвращает {@link Optional#empty()}.
     * Если поле присутствует, проверяет, что значение не пустое и является валидным UUID.</p>
     *
     * @param present   признак наличия поля в запросе
     * @param raw       строковое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с {@link Optional}, содержащим распарсенный {@link UUID},
     *         {@link Optional#empty()}, если поле отсутствует, или ошибкой
     *         {@code INVALID_ARGUMENT}, если переданное значение пустое или не является валидным UUID
     */
    public static Mono<Optional<UUID>> parseOptionalUuidOrInvalidArgument(
            boolean present,
            String raw,
            String fieldName
    ) {
        if (!present) {
            return Mono.just(Optional.empty());
        }

        return parseUuidOrInvalidArgument(raw, fieldName)
                .map(Optional::of);
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
     * Проверяет опциональное числовое значение на строго положительное значение.
     *
     * <p>Если поле отсутствует, возвращает {@link Optional#empty()}.
     * Если поле присутствует, значение должно быть больше нуля.</p>
     *
     * @param present   признак наличия поля в запросе
     * @param value     числовое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с {@link Optional}, содержащим значение,
     *         {@link Optional#empty()}, если поле отсутствует, или ошибкой
     *         {@code INVALID_ARGUMENT}, если значение меньше или равно нулю
     */
    public static Mono<Optional<Integer>> requireOptionalPositiveZeroOrInvalidArgument(
            boolean present,
            int value,
            String fieldName
    ) {
        if (!present) {
            return Mono.just(Optional.empty());
        }

        if (value < 0) {
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be positive")
                    .asRuntimeException());
        }

        return Mono.just(Optional.of(value));
    }

    /**
     * Проверяет опциональное числовое значение на положительность (> 0).
     * Если значение не указано (hasValue = false) - возвращает Optional.empty().
     * Если значение указано, должно быть строго больше нуля.
     *
     * @param hasValue  флаг, указывающий, что значение присутствует (optional поле в protobuf)
     * @param value     числовое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с {@link Optional} содержащим значение,
     *         или ошибкой {@code INVALID_ARGUMENT} если значение <= 0
     */
    public static Mono<Optional<Integer>> requireOptionalPositiveOrInvalidArgument(
            boolean hasValue,
            Integer value,
            String fieldName
    ) {
        if (!hasValue) {
            return Mono.just(Optional.empty());
        }
        if (value == null || value <= 0) {
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be positive")
                    .asRuntimeException());
        }
        return Mono.just(Optional.of(value));
    }

    /**
     * Проверяет опциональное числовое значение на положительность (>= 0).
     * Если значение не указано (hasValue = false) - возвращает Optional.empty().
     *
     * @param hasValue  флаг, указывающий, что значение присутствует (optional поле в protobuf)
     * @param value     числовое значение поля
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @return {@link Mono} с {@link Optional} содержащим значение,
     *         или ошибкой {@code INVALID_ARGUMENT} если значение <= 0
     */
    public static Mono<Optional<BigDecimal>> requireOptionalPositiveZeroOrInvalidArgument(
            boolean hasValue,
            Double value,
            String fieldName
    ) {
        if (!hasValue) {
            return Mono.just(Optional.empty());
        }
        if (value == null || value < 0) {
            return Mono.error(Status.INVALID_ARGUMENT
                                      .withDescription(fieldName + " must be positive")
                                      .asRuntimeException());
        }
        return Mono.just(Optional.of(BigDecimal.valueOf(value)));
    }

    /**
     * Проверяет, что дата начала не позже даты завершения, и возвращает распарсенные даты
     * в виде списка {@link Optional}.
     *
     * <p>Метод парсит переданные строковые представления дат в формате ISO {@code yyyy-MM-dd}
     * (если соответствующий флаг {@code hasValue*} установлен в {@code true}), после чего,
     * если обе даты присутствуют, проверяет, что дата начала не наступает позже даты
     * завершения.
     *
     * <p>Результатом является {@link Mono} со списком из двух элементов:
     * <ul>
     *     <li>элемент с индексом {@code 0} — {@link Optional#of(Object)} с датой начала,
     *     если {@code hasValueStart} равен {@code true}, иначе {@link Optional#empty()};</li>
     *     <li>элемент с индексом {@code 1} — {@link Optional#of(Object)} с датой завершения,
     *     если {@code hasValueDue} равен {@code true}, иначе {@link Optional#empty()}.</li>
     * </ul>
     *
     * @param hasValueStart  флаг, указывающий, что дата начала присутствует (optional поле в protobuf)
     * @param startDate      строковое представление даты начала в формате ISO {@code yyyy-MM-dd};
     * @param hasValueDue    флаг, указывающий, что дата окончания присутствует (optional поле в protobuf)
     * @param dueDate        строковое представление даты завершения в формате ISO {@code yyyy-MM-dd};
     * @param fieldNameStart наименование поля даты начала, используемое в сообщении об ошибке
     * @param fieldNameDue   наименование поля даты завершения, используемое в сообщении об ошибке
     * @return {@link Mono}, список из двух {@link Optional}: дата начала и дата
     *              завершения (в этом порядке)
     * @throws io.grpc.StatusRuntimeException (через {@link Mono#error(Throwable)}) со статусом
     *         {@code INVALID_ARGUMENT}, если:
     *         <ul>
     *             <li>{@code startDate} или {@code dueDate} не удалось распарсить как дату
     *             в формате ISO {@code yyyy-MM-dd};</li>
     *             <li>обе даты присутствуют и дата начала позже даты завершения.</li>
     *         </ul>
     */
    public static Mono<List<Optional<LocalDate>>> requireStartDateBeforeDueDate(
            boolean hasValueStart,
            String startDate,
            boolean hasValueDue,
            String dueDate,
            String fieldNameStart,
            String fieldNameDue) {

        LocalDate startLocalDate;
        LocalDate dueLocalDate;

        try {
            startLocalDate = hasValueStart ? LocalDate.parse(startDate) : null;
            dueLocalDate = hasValueDue ? LocalDate.parse(dueDate) : null;
        } catch (DateTimeParseException e) {
            return Mono.error(Status.INVALID_ARGUMENT
                                      .withDescription("Invalid date format, expected ISO yyyy-MM-dd: " + e.getParsedString())
                                      .asRuntimeException());
        }
        List<Optional<LocalDate>> optionalList = new ArrayList<>();

        if (hasValueStart && hasValueDue && startLocalDate.isAfter(dueLocalDate)) {
            return Mono.error(Status.INVALID_ARGUMENT
                                      .withDescription(fieldNameStart + " cannot be later than " + fieldNameDue)
                                      .asRuntimeException());
        }

        if (hasValueStart) {
            optionalList.add(Optional.of(startLocalDate));
        } else {
            optionalList.add(Optional.empty());
        }

        if (hasValueDue) {
            optionalList.add(Optional.of(dueLocalDate));
        } else {
            optionalList.add(Optional.empty());
        }

        return Mono.just(optionalList);
    }

    /**
     * Проверяет, что опциональное proto enum значение задано
     * и не является значением UNSPECIFIED.
     *
     * <p>Если поле отсутствует, возвращает {@link Optional#empty()}.
     * Если поле присутствует, его числовое значение не должно быть равно 0.</p>
     *
     * @param present   признак наличия поля в запросе
     * @param value     значение proto enum
     * @param fieldName имя поля (используется в сообщении об ошибке)
     * @param <T>       тип proto enum
     * @return {@link Mono} с {@link Optional}, содержащим значение,
     *         {@link Optional#empty()}, если поле отсутствует, или ошибкой
     *         {@code INVALID_ARGUMENT}, если передано значение UNSPECIFIED
     */
    public static <T extends ProtocolMessageEnum> Mono<Optional<T>>
    requireOptionalSpecifiedOrInvalidArgument(
            boolean present,
            T value,
            String fieldName
    ) {
        if (!present) {
            return Mono.just(Optional.empty());
        }

        return requireSpecifiedOrInvalidArgument(value, fieldName)
                .map(Optional::of);
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
                .switchIfEmpty(Mono.error(Status.INVALID_ARGUMENT
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
                .switchIfEmpty(Mono.error(Status.INVALID_ARGUMENT
                                                  .withDescription(fieldName + " must contain '@'")
                                                  .asRuntimeException()));
    }

    private static Mono<String> requireNonBlank(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return Mono.error(Status.INVALID_ARGUMENT
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
            return Mono.error(Status.INVALID_ARGUMENT
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
            return Mono.error(Status.INVALID_ARGUMENT
                    .withDescription(fieldName + " must be specified")
                    .asRuntimeException());
        }
        return Mono.just(value);
    }
}