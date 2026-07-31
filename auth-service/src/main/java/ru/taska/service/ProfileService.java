package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.UUID;

public interface ProfileService {

    /**
     * Генерирует presigned URL для скачивания аватара пользователя.
     *
     * @param actorUserId идентификатор пользователя
     * @return presigned URL для скачивания
     */
    Mono<String> getAvatarDownloadUrl(UUID actorUserId);

    /**
     * Получает профиль пользователя.
     *
     * @param userId идентификатор пользователя
     * @return профиль пользователя
     */
    Mono<UserProfileDto> getUserProfile(UUID userId);

    /**
     * Генерирует presigned URL для загрузки аватара пользователем напрямую в хранилище.
     * Валидирует content_type и размер файла перед генерацией ссылки.
     *
     * @param actorUserId идентификатор пользователя, запрашивающего загрузку
     * @param contentType MIME-тип загружаемого файла
     * @param sizeBytes   размер файла в байтах
     * @return ключ объекта и presigned URL для загрузки
     */
    Mono<PresignedUploadResult> createAvatarUploadUrl(UUID actorUserId, String contentType, long sizeBytes);

    /**
     * Подтверждает загрузку аватара после того, как фронтенд загрузил файл в хранилище.
     * Проверяет размер объекта и удаляет его, если размер превышает допустимый.
     * Если у пользователя уже есть аватар, старый удаляется из хранилища и базы данных.
     *
     * @param actorUserId идентификатор пользователя
     * @param objectKey   ключ объекта в хранилище
     * @param fileName    оригинальное имя файла
     * @param contentType MIME-тип файла
     * @return данные о сохранённом аватаре
     */
    Mono<AvatarDto> confirmAvatarUpload(UUID actorUserId, String objectKey, String fileName, String contentType);

    /**
     * Удаляет аватар пользователя из базы данных и хранилища.
     * Операция идемпотентна: если аватар отсутствует, завершается успешно.
     *
     * @param actorUserId идентификатор пользователя
     */
    Mono<Void> deleteMyAvatar(UUID actorUserId);
}
