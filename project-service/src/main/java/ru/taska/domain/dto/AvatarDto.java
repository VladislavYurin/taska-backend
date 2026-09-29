package ru.taska.domain.dto;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Value
@SuperBuilder
public class AvatarDto extends BaseAvatarDto{
    UUID id;
    UUID userId;
    String objectKey;
    String fileName;
    String contentType;
    Long sizeBytes;
}