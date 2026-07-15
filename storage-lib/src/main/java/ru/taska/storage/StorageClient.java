package ru.taska.storage;

import java.io.InputStream;

/**
 * Абстракция над S3-совместимым хранилищем.
 * Позволяет сервисам работать с файлами не зная о деталях реализации.
 */
public interface StorageClient {

    /**
     * Загружает файл в хранилище через бэкенд.
     * Генерирует objectKey и возвращает его.
     */
    String putObject(InputStream data, String contentType, long contentLength);

    /**
     * Скачивает файл из хранилища через бэкенд и возвращает поток байт.
     */
    InputStream getObject(String objectKey);

    /**
     * Удаляет файл из хранилища.
     */
    void deleteObject(String objectKey);

    /**
     * Генерирует presigned URL для загрузки файла фронтендом напрямую в хранилище.
     * Возвращает objectKey (для сохранения в БД) и временную ссылку (для фронтенда).
     *
     * <p><b>Предпочтительный способ загрузки для фронтенда</b> — файл передаётся напрямую
     * в хранилище, минуя бэкенд.</p>
     */
    PresignedUploadResult createPresignedUploadUrl(String contentType);

    /**
     * Генерирует presigned URL для скачивания файла фронтендом напрямую из хранилища.
     *
     * <p><b>Предпочтительный способ скачивания для фронтенда</b> — файл передаётся напрямую
     * из хранилища, минуя бэкенд.</p>
     */
    String createPresignedDownloadUrl(String objectKey);

    /**
     * Проверяет размер загруженного объекта.
     * Вызывается после того как фронтенд загрузил файл напрямую в хранилище по presigned URL.
     * Если размер превышает допустимый — удаляет объект и бросает исключение.
     */
    void validateObjectSizeAndDeleteIfNeeded(String objectKey);

    /**
     * Проверяет существование объекта в хранилище.
     */
    boolean objectExists(String objectKey);
}
