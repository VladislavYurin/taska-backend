package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.event.OutboxEventStatus;
import ru.taska.event.payload.projectService.MemberAddedPayload;
import ru.taska.event.payload.projectService.MemberRemovedPayload;
import ru.taska.event.payload.projectService.MemberUpdatedPayload;
import ru.taska.event.payload.projectService.ProjectCreatedPayload;
import ru.taska.event.payload.projectService.ProjectUpdatedPayload;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.OutboxEventService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final String PROJECT_CREATED = EventType.PROJECT_CREATED.getValue();
    private static final String MEMBER_ADDED = EventType.MEMBER_ADDED.getValue();
    private static final String MEMBER_REMOVED = EventType.MEMBER_REMOVED.getValue();
    private static final String PROJECT_UPDATED = EventType.PROJECT_UPDATED.getValue();

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;

    @Override
    public Mono<OutboxEvent> saveProjectCreated(String requestId, String nodeId, Project project) {
        OutboxEvent event = OutboxEvent.builder()
                                       .aggregateType(AggregateType.PROJECT.getValue())
                                       .aggregateId(project.getId())
                                       .eventType(PROJECT_CREATED)
                                       .payload(projectCreatedPayload(project))
                                       .attempts(0)
                                       .status(OutboxEventStatus.NEW)
                                       .requestId(requestId)
                                       .build();

        log.debug("[{}][{}] Подготовка outbox-события [ {} ] для проекта [ ID = {} ]",
                requestId, nodeId, EventType.PROJECT_CREATED, project.getId());
        return outboxEventRepository.save(event);
    }

    @Override
    public Mono<OutboxEvent> saveMemberAdded(String requestId, String nodeId, ProjectMember member) {
        OutboxEvent event = OutboxEvent.builder()
                                       .aggregateType(AggregateType.PROJECT.getValue())
                                       .aggregateId(member.getProjectId())
                                       .eventType(MEMBER_ADDED)
                                                                              .attempts(0)
                                       .status(OutboxEventStatus.NEW)
                                       .requestId(requestId)
                                       .build();

        log.debug("[{}][{}] Подготовка outbox-события [ {} ] для участника [ ID = {} ] проекта [ ID = {} ]",
                  requestId, nodeId, EventType.MEMBER_ADDED, member.getUserId(), member.getProjectId());
        return saveMemberEvent(event, key -> memberAddedPayload(member, key));
    }

    @Override
    public Mono<OutboxEvent> saveMemberRemoved(String requestId, String nodeId, UUID deletedMemberId, UUID projectId){
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AggregateType.PROJECT.getValue())
                .aggregateId(projectId)
                .eventType(MEMBER_REMOVED)
                                .attempts(0)
                .status(OutboxEventStatus.NEW)
                .requestId(requestId)
                .build();

        log.debug("[{}][{}] Подготовка outbox-события [ {} ] для участника [ ID = {} ] проекта [ ID = {} ]",
                  requestId, nodeId, EventType.MEMBER_REMOVED, deletedMemberId, projectId);
        return saveMemberEvent(event, key -> memberRemovedPayload(deletedMemberId, projectId, key));
    }

    @Override
    public Mono<OutboxEvent> saveMemberUpdated(String requestId, String nodeId, UUID updatedMemberId,
                                               ProjectRole role, UUID projectId) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AggregateType.PROJECT.getValue())
                .aggregateId(projectId)
                .eventType(EventType.MEMBER_UPDATED.getValue())
                                .attempts(0)
                .status(OutboxEventStatus.NEW)
                .build();
        log.debug("[{}][{}] Подготовка outbox-события [ {} ] для участника [ ID = {} ] проекта [ ID = {} ]",
                requestId, nodeId, EventType.MEMBER_UPDATED.getValue(), updatedMemberId, projectId);
        return saveMemberEvent(event, key -> memberUpdatedPayload(updatedMemberId, role, projectId, key));
    }

    @Override
    public Mono<OutboxEvent> saveProjectUpdated(String requestId, String nodeId, Project project, UUID actorUserId){
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AggregateType.PROJECT.getValue())
                .aggregateId(project.getId())
                .eventType(PROJECT_UPDATED)
                .payload(projectUpdatePayload(project, actorUserId))
                .attempts(0)
                .status(OutboxEventStatus.NEW)
                .requestId(requestId)
                .build();

        log.debug("[{}][{}] Подготовка outbox-события [ {} ] для проекта [ ID = {} ]",
                requestId, nodeId, EventType.PROJECT_UPDATED, project.getId());
        return outboxEventRepository.save(event);
    }

    private Mono<OutboxEvent> saveMemberEvent(OutboxEvent event, java.util.function.Function<String, JsonNode> payload) {
        return projectRepository.findById(event.getAggregateId())
                .map(project -> java.util.Objects.toString(project.getProjectKey(), ""))
                .defaultIfEmpty("")
                .flatMap(key -> {
                    event.setPayload(payload.apply(key));
                    return outboxEventRepository.save(event);
                });
    }

    private JsonNode projectUpdatePayload(Project project , UUID actorUserId){
        return objectMapper.valueToTree(
                new ProjectUpdatedPayload(
                        project.getId(),
                        project.getName(),
                        project.getDescription(),
                        project.getColor(),
                        actorUserId
                )
        );
    }

    private JsonNode projectCreatedPayload(Project project) {
        return objectMapper.valueToTree(
                new ProjectCreatedPayload(
                        project.getId(),
                        project.getProjectKey(),
                        project.getCreatedBy()
                )
        );
    }

    private JsonNode memberAddedPayload(ProjectMember member, String projectKey) {
        return objectMapper.valueToTree(
                new MemberAddedPayload(
                        member.getProjectId(),
                        member.getUserId(),
                        member.getRole().toString(),
                        member.getAddedBy(),
                        projectKey
                )
        );
    }

    private JsonNode memberUpdatedPayload(UUID userId, ProjectRole role, UUID projectId, String projectKey) {
        return objectMapper.valueToTree(
                new MemberUpdatedPayload(
                        userId,
                        String.valueOf(role),
                        projectId,
                        projectKey
                )
        );
    }

    private JsonNode memberRemovedPayload(UUID deletedMemberId, UUID projectId, String projectKey) {
        return objectMapper.valueToTree(
                new MemberRemovedPayload(
                        projectId,
                        deletedMemberId,
                        projectKey
                )
        );
    }
}
