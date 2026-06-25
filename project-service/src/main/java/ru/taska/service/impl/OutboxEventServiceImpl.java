package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.event.EventType;
import ru.taska.event.OutboxEventStatus;
import ru.taska.event.payload.projectService.MemberAddedPayload;
import ru.taska.event.payload.projectService.MemberRemovedPayload;
import ru.taska.event.payload.projectService.ProjectCreatedPayload;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.OutboxEventService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private static final String PROJECT_AGREGATE_TYPE = "PROJECT";
    private static final String MEMBER_AGREGATE_TYPE = "PROJECT_MEMBER";

    private static final String PROJECT_CREATED = EventType.PROJECT_CREATED.getValue();
    private static final String MEMBER_ADDED = EventType.MEMBER_ADDED.getValue();
    private static final String MEMBER_REMOVED = EventType.MEMBER_REMOVED.getValue();

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<OutboxEvent> saveProjectCreated(String requestId, Project project) {
        OutboxEvent event = OutboxEvent.builder()
                                       .aggregateType(PROJECT_AGREGATE_TYPE)
                                       .aggregateId(project.getId())
                                       .eventType(PROJECT_CREATED)
                                       .payload(projectCreatedPayload(project))
                                       .attempts(0)
                                       .status(OutboxEventStatus.NEW)
                                       .requestId(requestId)
                                       .build();

        log.debug("Подготовка outbox-события [ {} ] для проекта [ ID = {} ]", "ProjectCreated", project.getId());
        return outboxEventRepository.save(event);
    }

    @Override
    public Mono<OutboxEvent> saveMemberAdded(String requestId, ProjectMember member) {
        OutboxEvent event = OutboxEvent.builder()
                                       .aggregateType(MEMBER_AGREGATE_TYPE)
                                       .aggregateId(member.getProjectId())
                                       .eventType(MEMBER_ADDED)
                                       .payload(memberAddedPayload(member))
                                       .attempts(0)
                                       .status(OutboxEventStatus.NEW)
                                       .requestId(requestId)
                                       .build();

        log.debug("Подготовка outbox-события [ {} ] для участника [ ID = {} ] проекта [ ID = {} ]",
                  "MemberAdded", member.getUserId(), member.getProjectId());
        return outboxEventRepository.save(event);
    }

    @Override
    public Mono<OutboxEvent> saveMemberRemoved(String requestId, UUID deletedMemberId, UUID projectId){
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(MEMBER_AGREGATE_TYPE)
                .aggregateId(projectId)
                .eventType(MEMBER_REMOVED)
                .payload(memberRemovedPayload(deletedMemberId, projectId))
                .attempts(0)
                .status(OutboxEventStatus.NEW)
                .requestId(requestId)
                .build();

        log.debug("Подготовка outbox-события [ {} ] для участника [ ID = {} ] проекта [ ID = {} ]",
                  "MemberRemoved", deletedMemberId, projectId);
        return outboxEventRepository.save(event);
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

    private JsonNode memberAddedPayload(ProjectMember member) {
        return objectMapper.valueToTree(
                new MemberAddedPayload(
                        member.getProjectId(),
                        member.getUserId(),
                        member.getRole().toString(),
                        member.getAddedBy()
                )
        );
    }

    private JsonNode memberRemovedPayload(UUID deletedMemberId, UUID projectId) {
        return objectMapper.valueToTree(
                new MemberRemovedPayload(
                        projectId,
                        deletedMemberId
                )
        );
    }
}
