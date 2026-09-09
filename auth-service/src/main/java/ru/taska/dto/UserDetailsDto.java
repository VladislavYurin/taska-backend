package ru.taska.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record UserDetailsDto(
        UUID userId,
        String displayName,
        String email,
        AvatarDto avatar
) {
}