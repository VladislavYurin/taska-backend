package ru.taska.error;

/**
 * Тело ответа при ошибке обработки запроса.
 * Возвращается клиенту в формате JSON с соответствующим HTTP-статусом.
 *
 * @param code      код ошибки — название HTTP-статуса либо gRPC-статуса,
 *                  преобразованного в HTTP (например, {@code UNAUTHORIZED}, {@code NOT_FOUND})
 * @param message   человекочитаемое описание ошибки
 * @param requestId идентификатор запроса, при обработке которого возникла ошибка
 */
public record RestErrorResponse(
        String code,
        String message,
        String requestId
) {

}
