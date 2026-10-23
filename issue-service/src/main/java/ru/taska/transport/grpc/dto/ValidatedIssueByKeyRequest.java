package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Результат валидации входных данных запроса получения задачи по ключу.
 * <p>
 * Содержит типизированные значения, прошедшие проверку формата.
 * Используется для передачи валидированных данных из gRPC-слоя
 * в сервис получения информации о задаче.
 */
public record ValidatedIssueByKeyRequest(
    String requestId,
    String nodeId,
    String issueKey,
    UUID actorUserId
) { }

