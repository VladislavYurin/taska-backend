package ru.taska.domain;

/**
 * Промежуточный класс для передачи данных от сервисного слоя к grpc-слою.
 * Содержит сущность IssueAttachment и URL для загрузки изображения
 */
public record AttachmentDto(
        IssueAttachment issueAttachment,
        String url
) {
}
