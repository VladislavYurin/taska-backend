package ru.taska.dto;

import java.util.Map;
import java.util.UUID;
import ru.taska.domain.StatusCategory;

public record WorkflowMutation(String requestId, String nodeId, UUID actor, UUID project, UUID workflow,
                               Integer version, UUID status, UUID transition, String name,
                               StatusCategory category, Integer sortOrder, Boolean hidden,
                               String targetStatusKey, Map<String, String> statusMappings) {
}
