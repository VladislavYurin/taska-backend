package exception;

import lombok.Getter;

/**
 * Базовое доменное исключение.
 *
 * <p>Используется для передачи ошибок из сервисного слоя в транспортный.
 * Транспортный слой (например, {@code GrpcExceptionMapper}) преобразует
 * его в соответствующий статус ответа.</p>
 */
@Getter
public class DomainException extends RuntimeException {

    private final DomainStatus status;

    /**
     * @param status  статус ошибки, определяющий код ответа
     * @param message человекочитаемое описание ошибки
     */
    public DomainException(DomainStatus status, String message) {
        super(message);
        this.status = status;
    }
}