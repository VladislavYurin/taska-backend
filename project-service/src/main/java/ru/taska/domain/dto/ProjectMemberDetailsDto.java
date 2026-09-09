package ru.taska.domain.dto;

import lombok.Builder;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

@Builder
public record ProjectMemberDetailsDto(
        UUID userId,
        ProjectRole role,
        String displayName,
        String email,
        AvatarDto avatar
) {}
