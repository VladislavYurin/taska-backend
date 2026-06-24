package ru.taska.domain;

import lombok.Builder;

@Builder
public record ProjectMembershipInfoDto(
        ProjectRole role,
        Boolean isMember,
        Boolean isProjectExists
) {
}
