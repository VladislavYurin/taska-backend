package ru.taska.domain.dto;

import lombok.Builder;
import ru.taska.domain.ProjectRole;

@Builder
public record ProjectAccessInfoDto(
        ProjectRole role,
        Boolean isMember,
        Boolean isProjectExists,
        Boolean isProjectArchived
) {
}
