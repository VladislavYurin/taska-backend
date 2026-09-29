package ru.taska.domain.dto;

import lombok.Builder;
import lombok.Value;

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
    String downloadUrl;
}