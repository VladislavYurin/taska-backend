package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

public interface OutboxEventService {

    Mono<OutboxEvent> saveProjectCreated(String requestId, String nodeId, Project project);

    Mono<OutboxEvent> saveMemberAdded(String requestId, String nodeId, ProjectMember member);

    Mono<OutboxEvent> saveMemberRemoved(String requestId, String nodeId, UUID deletedMemberId, UUID projectId);

    Mono<OutboxEvent> saveMemberUpdated(String requestId, String nodeId, UUID updatedMemberId,
                                        ProjectRole role, UUID projectId);

}
