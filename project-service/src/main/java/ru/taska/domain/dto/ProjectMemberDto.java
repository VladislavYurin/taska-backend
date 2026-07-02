package ru.taska.domain.dto;

import lombok.Builder;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

@Builder
public record ProjectMemberDto(
        UUID userId,
        ProjectRole role
) {
}
