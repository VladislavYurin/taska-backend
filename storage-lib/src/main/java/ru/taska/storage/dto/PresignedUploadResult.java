package ru.taska.storage.dto;

/**
 * Результат генерации presigned URL для загрузки файла.
 * Содержит ключ объекта для сохранения в БД и временную ссылку для фронтенда.
 */
public record PresignedUploadResult(String objectKey, String url) {
}
