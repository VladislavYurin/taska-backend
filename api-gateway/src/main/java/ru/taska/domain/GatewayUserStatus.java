package ru.taska.domain;

/**
 * Перечисление, определяющее возможные статусы жизненного цикла учетной записи пользователя.
 * <p>
 * Служит внутренней доменной моделью API Gateway, очищенной от префиксов gRPC-слоя
 * (таких как {@code USER_STATUS_}), и используется для передачи статуса в REST-ответах клиенту.
 */
public enum GatewayUserStatus {
        UNSPECIFIED,
        INVITED,
        ACTIVE,
        BLOCKED
}
