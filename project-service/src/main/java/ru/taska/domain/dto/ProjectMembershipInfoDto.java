package ru.taska.domain.dto;

import lombok.Builder;
import ru.taska.domain.ProjectRole;

@Builder
public record ProjectMembershipInfoDto(
        ProjectRole role,
        Boolean isMember,
        Boolean isProjectExists,
        boolean projectArchived
) {
    public ProjectMembershipInfoDto(ProjectRole role, Boolean isMember, Boolean isProjectExists) {
        this(role, isMember, isProjectExists, false);
    }
}
