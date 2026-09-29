package ru.taska.domain.dto;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String displayName,
        String avatarUrl
) {
}
