package ru.taska.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Вложение (файл), прикреплённое к задаче.
 *
 * <p>Хранит метаданные загруженного файла.
 * Поддерживает мягкое удаление через поле {@code deletedAt}.</p>
 */
@Data
@NoArgsConstructor
@Table(name = "issue_attachments", schema = "taska")
public class IssueAttachment {

    /**
     * Идентификатор вложения.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор задачи, к которой прикреплено вложение.
     */
    @Column("issue_id")
    private UUID issueId;

    /**
     * Идентификатор пользователя, загрузившего файл.
     */
    @Column("uploaded_by")
    private UUID uploadedBy;

    /**
     * Ключ объекта в файловом хранилище (S3-совместимом).
     */
    @Column("object_key")
    private String objectKey;

    /**
     * Оригинальное имя файла.
     */
    @Column("file_name")
    private String fileName;

    /**
     * MIME-тип содержимого файла.
     */
    @Column("content_type")
    private String contentType;

    /**
     * Размер файла в байтах.
     */
    @Column("size_bytes")
    private Long sizeBytes;

    /**
     * Контрольная сумма файла.
     */
    @Column("checksum")
    private String checksum;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка мягкого удаления (null — запись активна).
     */
    @Column("deleted_at")
    private Instant deletedAt;

    /**
     * Создаёт новое вложение с заданными метаданными.
     * Поля {@code id}, {@code checksum}, {@code createdAt}, {@code deletedAt} не задаются —
     * они заполняются автоматически при сохранении.
     */
    public static IssueAttachment createNewAttachment(
            UUID issueId,
            UUID uploadedBy,
            String objectKey,
            String fileName,
            String contentType,
            Long sizeBytes,
            String checksum
    ) {
        IssueAttachment attachment = new IssueAttachment();
        attachment.setIssueId(issueId);
        attachment.setUploadedBy(uploadedBy);
        attachment.setObjectKey(objectKey);
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setChecksum(checksum);
        return attachment;
    }
}
