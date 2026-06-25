package ru.taska.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;

public interface OutboxEventService {

    Mono<OutboxEvent> saveProjectCreated(String requestId, Project project);

    Mono<OutboxEvent> saveMemberAdded(String requestId, ProjectMember member);

    Mono<OutboxEvent> saveMemberRemoved(String requestId, UUID deletedMemberId, UUID projectId);

}
