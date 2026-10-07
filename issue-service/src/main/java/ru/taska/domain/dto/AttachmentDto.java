package ru.taska.domain.dto;

import ru.taska.domain.entity.IssueAttachment;

/**
 * Промежуточный класс для передачи данных от сервисного слоя к grpc-слою.
 * Содержит сущность IssueAttachment и URL для загрузки изображения
 */
public record AttachmentDto(
        IssueAttachment issueAttachment,
        String url
) {
}
