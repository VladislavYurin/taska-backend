package ru.taska.service;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.event.payload.projectService.ProjectArchivedPayload;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ProjectArchiveService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final OutboxEventRepository outbox;
    private final ObjectMapper json;

    @Transactional
    public Mono<Project> archive(UUID project, UUID actor, String requestId, String nodeId) {
        return projects.findByIdForUpdate(project)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found")))
                .flatMap(current -> members.findByUserIdAndProjectIdForShare(actor, project)
                        .filter(member -> member.getRole() == ProjectRole.ADMIN)
                        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Project ADMIN role is required")))
                        .flatMap(member -> {
                            if (current.getArchivedAt() != null) return Mono.just(current);
                            current.setArchivedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                            return projects.save(current).flatMap(saved -> members.findProjectMembers(project, actor).collectList()
                                    .flatMap(all -> outbox.save(OutboxEvent.builder()
                                            .aggregateType(AggregateType.PROJECT.getValue()).aggregateId(project)
                                            .eventType(EventType.PROJECT_ARCHIVED.getValue()).requestId(requestId)
                                            .attempts(0).status(ru.taska.event.OutboxEventStatus.NEW)
                                            .payload(json.valueToTree(new ProjectArchivedPayload(project, saved.getProjectKey(), saved.getName(),
                                                    actor, saved.getArchivedAt(), all.stream().map(m -> m.userId()).distinct().toList())))
                                            .build()).thenReturn(saved)));
                        }));
    }
}
