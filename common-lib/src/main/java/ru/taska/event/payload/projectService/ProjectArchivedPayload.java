package ru.taska.event.payload.projectService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectArchivedPayload(UUID projectId, String projectKey, String projectName,
                                     UUID actorUserId, Instant archivedAt, List<UUID> memberIds) { }
