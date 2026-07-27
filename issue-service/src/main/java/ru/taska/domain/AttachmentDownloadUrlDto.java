package ru.taska.domain;

public record AttachmentDownloadUrlDto(
        String url,
        String checksum
) {
}
