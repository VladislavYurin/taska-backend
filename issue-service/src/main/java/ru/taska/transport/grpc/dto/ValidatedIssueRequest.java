package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Результат валидации входных данных запроса получения задачи.
 * <p>
 * Содержит типизированные значения, прошедшие проверку формата.
 * Используется для передачи валидированных данных из gRPC-слоя
 * в сервис получения информации о задаче.
 */
public record ValidatedIssueRequest (
    String requestId,
    String nodeId,
    UUID issueId,
    UUID actorUserId
) { }

