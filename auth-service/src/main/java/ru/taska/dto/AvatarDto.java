package ru.taska.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AvatarDto {
    @Column("id")
    UUID id;
    UUID userId;
    String objectKey;
    String fileName;
    String contentType;
    Long sizeBytes;
    Instant createdAt;
    String downloadUrl;
}
