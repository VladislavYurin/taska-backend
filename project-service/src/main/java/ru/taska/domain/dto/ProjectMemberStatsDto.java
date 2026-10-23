package ru.taska.domain.dto;

import java.util.List;
import java.util.UUID;

public record ProjectMemberStatsDto(UUID projectId, long memberCount, List<ProjectMemberDetailsDto> members) {
}
