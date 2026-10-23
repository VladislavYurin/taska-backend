package ru.taska.domain.dto;

public record AttachmentDownloadUrlDto(
        String url,
        String checksum
) {
}
