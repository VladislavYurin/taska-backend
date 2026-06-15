package ru.taska.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;

public interface OutboxEventService {

    Mono<OutboxEvent> saveProjectCreated(Project project);

    Mono<OutboxEvent> saveMemberAdded(ProjectMember member);

    Mono<OutboxEvent> saveMemberRemoved(UUID deletedMemberId, UUID projectId);

}
