package exception;

/**
 * Статусы доменных исключений, совместимые со статусами gRPC и HTTP.
 *
 * <p>Маппинг на gRPC и HTTP коды:</p>
 * <pre>
 * DomainStatus         gRPC статус            HTTP код
 * ─────────────────────────────────────────────────────
 * NOT_FOUND            NOT_FOUND              404
 * ALREADY_EXISTS       ALREADY_EXISTS         409
 * INVALID_ARGUMENT     INVALID_ARGUMENT       400
 * FAILED_PRECONDITION  FAILED_PRECONDITION    400
 * PERMISSION_DENIED    PERMISSION_DENIED      403
 * UNAUTHENTICATED      UNAUTHENTICATED        401
 * RESOURCE_EXHAUSTED   RESOURCE_EXHAUSTED     429
 * UNAVAILABLE          UNAVAILABLE            503
 * DEADLINE_EXCEEDED    DEADLINE_EXCEEDED      504
 * CANCELLED            CANCELLED              499
 * ABORTED              ABORTED                409
 * OUT_OF_RANGE         OUT_OF_RANGE           400
 * DATA_LOSS            DATA_LOSS              500
 * UNIMPLEMENTED        UNIMPLEMENTED          501
 * UNKNOWN              UNKNOWN                500
 * INTERNAL             INTERNAL               500
 * </pre>
 */
public enum DomainStatus {

    /** Запрошенный ресурс не найден. */
    NOT_FOUND,

    /** Ресурс уже существует. */
    ALREADY_EXISTS,

    /** Некорректный аргумент запроса. */
    INVALID_ARGUMENT,

    /** Предусловие не выполнено. */
    FAILED_PRECONDITION,

    /** Недостаточно прав для выполнения операции. */
    PERMISSION_DENIED,

    /** Не аутентифицирован. */
    UNAUTHENTICATED,

    /** Превышен лимит ресурсов (rate limit и т.п.). */
    RESOURCE_EXHAUSTED,

    /** Сервис временно недоступен (например, БД недоступна). */
    UNAVAILABLE,

    /** Превышен дедлайн выполнения операции. */
    DEADLINE_EXCEEDED,

    /** Операция отменена клиентом. */
    CANCELLED,

    /** Операция прервана из-за конкурентного изменения (например, конфликт транзакций). */
    ABORTED,

    /** Значение вышло за допустимый диапазон. */
    OUT_OF_RANGE,

    /** Невосстановимая потеря данных. */
    DATA_LOSS,

    /** Операция не реализована. */
    UNIMPLEMENTED,

    /** Неизвестная ошибка. */
    UNKNOWN,

    /** Внутренняя ошибка сервера. */
    INTERNAL
}