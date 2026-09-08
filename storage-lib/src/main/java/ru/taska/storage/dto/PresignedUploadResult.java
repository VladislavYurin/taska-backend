package ru.taska.storage.dto;

/**
 * Результат генерации presigned URL для загрузки файла.
 * <p>Содержит:
 * <li>Ключ объекта для сохранения в БД</li>
 * <li>Временную ссылку для фронтенда</li>
 * <li>Время (кол-во секунд), через которое ссылка станет недействительной</li>
 * </p>
 */
public record PresignedUploadResult(
        String objectKey,
        String url,
        long expiresIn
){}
