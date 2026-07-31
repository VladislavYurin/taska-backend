package ru.taska.storage.dto;

/**
 * Метаданные объекта, хранящегося в S3-совместимом хранилище.
 *
 * @param checksum контрольная сумма (ETag) объекта.
 * @param sizeBytes фактический размер объекта в байтах.
 */
public record StoredObjectMetadata(String checksum, long sizeBytes) {
}
