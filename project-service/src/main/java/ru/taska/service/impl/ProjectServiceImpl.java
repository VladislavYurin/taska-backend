package ru.taska.service.impl;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.entity.*;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.ProjectService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSettingRepository projectSettingRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;


    @Override
    @Transactional
    public Mono<ProjectResponse> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId) {
        return projectRepository.findByProjectKey(projectKey)
                .switchIfEmpty(Mono.defer(() -> {
                    Project project = Project.builder()
                            .projectKey(projectKey)
                            .name(projectName)
                            .createdBy(userId)
                            .build();
                    return projectRepository.save(project);
                }))
                .flatMap(savedProject -> {
                    ProjectMember adminMember = ProjectMember.builder()
                            .projectId(savedProject.getId())
                            .userId(savedProject.getCreatedBy())
                            .role(ProjectRole.ADMIN)
                            .addedBy(savedProject.getCreatedBy())
                            .addedAt(Instant.now())
                            .build();

                    ObjectNode defaultSettingsJson = objectMapper.createObjectNode();

                    ProjectSetting defaultSettings = ProjectSetting.builder()
                            .projectId(savedProject.getId())
                            .settings(defaultSettingsJson)
                            .updatedBy(savedProject.getCreatedBy())
                            .updatedAt(Instant.now())
                            .build();

                    ObjectNode eventPayload = objectMapper.createObjectNode();
                    eventPayload.put("projectId", savedProject.getId().toString());
                    eventPayload.put("projectKey", savedProject.getProjectKey());
                    eventPayload.put("name", savedProject.getName());
                    eventPayload.put("createdBy", savedProject.getCreatedBy().toString());

                    OutboxEvent outboxEvent = OutboxEvent.builder()
                            .aggregateType("PROJECT")
                            .aggregateId(savedProject.getId())
                            .eventType("CREATED")
                            .payload(eventPayload)
                            .attempts(0)
                            .createdAt(Instant.now())
                            .build();

                    return projectMemberRepository.save(adminMember)
                            .then(projectSettingRepository.save(defaultSettings))
                            .then(outboxEventRepository.save(outboxEvent))
                            .then(Mono.defer(() -> {
                                log.info("[{}][{}] Project successfully created: projectKey={}, projectName={}, userId={}", requestId, nodeId, projectKey, projectName, userId);
                                return Mono.just(savedProject);
                            }));
                })
                .map(projectMapper::toResponse);

    }

    @Override
    @Transactional(readOnly = true)
    public Mono<ProjectResponse> getProject(String requestId, String nodeId, UUID projectId) {
        return projectRepository.findById(projectId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project for projectId = " + projectId + " was not found ")))
                .map(projectMapper::toResponse)
                .doOnSuccess(p -> log.info("[{}][{}] Successfully getting project with id: {}", requestId, nodeId, projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<ProjectResponse> listMyProjects(String requestId, String nodeId, UUID userId) {
        return projectRepository.findAllByMemberUserId(userId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Not found projects for user with id: " + userId)))
                .map(projectMapper::toResponse)
                .doOnComplete(() -> log.info("[{}][{}] Successfully getting all projects by user id: {}", requestId, nodeId, userId));
    }
}