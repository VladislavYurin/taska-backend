package ru.taska.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class AvatarDto {
    UUID id;
    UUID userId;
    String objectKey;
    String fileName;
    String contentType;
    Long sizeBytes;
    Instant createdAt;
    String downloadUrl;
}
