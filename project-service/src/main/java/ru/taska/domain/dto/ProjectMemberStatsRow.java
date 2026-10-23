package ru.taska.domain.dto;

import java.time.Instant;
import java.util.UUID;
import ru.taska.domain.ProjectRole;

public record ProjectMemberStatsRow(UUID projectId, UUID userId, ProjectRole role,
                                    Instant addedAt, long memberCount) {
}
