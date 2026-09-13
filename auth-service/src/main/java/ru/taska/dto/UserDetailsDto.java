package ru.taska.dto;

import lombok.Builder;
import org.springframework.data.relational.core.mapping.Embedded;

import java.util.UUID;

@Builder
public record UserDetailsDto(
        UUID userId,
        String displayName,
        String email,
        @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL, prefix = "avatar_")
        AvatarDto avatar
) {
}