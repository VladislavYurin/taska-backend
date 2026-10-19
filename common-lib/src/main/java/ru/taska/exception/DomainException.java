package ru.taska.exception;

import lombok.Getter;

import java.util.Map;

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
    private final Map<String, String> details;

    /**
     * @param status  статус ошибки, определяющий код ответа
     * @param message человекочитаемое описание ошибки
     */
    public DomainException(DomainStatus status, String message) {
        this(status, message, Map.of());
    }

    public DomainException(DomainStatus status, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.details = Map.copyOf(details);
    }
}
