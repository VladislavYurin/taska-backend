package ru.taska.service.attachment;

import reactor.core.publisher.Mono;
import ru.taska.domain.AttachmentDownloadUrlDto;
import ru.taska.domain.AttachmentDto;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.List;
import java.util.UUID;

/**
 * Сервис для управления вложениями задач.
 */
public interface AttachmentService {

    /**
     * Генерирует presigned URL для загрузки файла в хранилище.
     *
     * @param requestId   идентификатор запроса.
     * @param nodeId      идентификатор узла.
     * @param issueId     идентификатор задачи.
     * @param actorUserId идентификатор пользователя, выполняющего загрузку.
     * @param contentType MIME-тип загружаемого файла.
     * @param sizeBytes   размер файла в байтах.
     * @return Mono с результатом генерации presigned URL.
     */
    Mono<PresignedUploadResult> createUploadUrl(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            String contentType,
            long sizeBytes
    );

    /**
     * Подтверждает загрузку файла и сохраняет метаданные вложения в БД.
     *
     * @param requestId   идентификатор запроса.
     * @param nodeId      идентификатор узла.
     * @param issueId     идентификатор задачи.
     * @param actorUserId идентификатор пользователя, выполняющего загрузку.
     * @param objectKey   ключ объекта в хранилище.
     * @param fileName    оригинальное имя файла.
     * @param contentType MIME-тип файла.
     * @return Mono с сохранённым вложением.
     */
    Mono<AttachmentDto> confirmUpload(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            String objectKey,
            String fileName,
            String contentType
    );

    /**
     * Возвращает список вложений задачи.
     *
     * @param requestId   идентификатор запроса.
     * @param nodeId      идентификатор узла.
     * @param issueId     идентификатор задачи.
     * @param actorUserId идентификатор пользователя, запрашивающего список.
     * @return Mono со списком вложений.
     */
    Mono<List<AttachmentDto>> listAttachments(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

    /**
     * Генерирует presigned URL для скачивания вложения.
     *
     * @param requestId    идентификатор запроса.
     * @param nodeId       идентификатор узла.
     * @param attachmentId идентификатор вложения.
     * @param actorUserId  идентификатор пользователя, запрашивающего скачивание.
     * @return Mono с presigned URL для скачивания.
     */
    Mono<AttachmentDownloadUrlDto> getDownloadUrl(
            String requestId,
            String nodeId,
            UUID attachmentId,
            UUID actorUserId
    );

    /**
     * Выполняет мягкое удаление вложения.
     *
     * @param requestId    идентификатор запроса.
     * @param nodeId       идентификатор узла.
     * @param attachmentId идентификатор вложения.
     * @param actorUserId  идентификатор пользователя, выполняющего удаление.
     * @return Mono<Void> сигнализирующий о завершении операции.
     */
    Mono<Void> deleteAttachment(
            String requestId,
            String nodeId,
            UUID attachmentId,
            UUID actorUserId
    );
}
