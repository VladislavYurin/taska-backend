package ru.taska.service.impl;

import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.Project;
import ru.taska.entity.ProjectMember;
import ru.taska.entity.ProjectRole;
import ru.taska.entity.ProjectSetting;
import ru.taska.exception.ProjectAlreadyExistsException;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.ProjectService;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSettingRepository projectSettingRepository;
    private final OutboxEventRepository outboxEventRepository; // Твой репозиторий
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Mono<Project> createProject(String projectKey, String name, String userIdStr) {

        if (projectKey == null || projectKey.isBlank()) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Project key is required").asRuntimeException());
        }
        if (name == null || name.isBlank()) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Project name is required").asRuntimeException());
        }
        if (userIdStr == null || userIdStr.isBlank()) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("UserId is required").asRuntimeException());
        }

        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Invalid UserId UUID format").asRuntimeException());
        }

        return projectRepository.findByProjectKey(projectKey)
                .flatMap(existing -> Mono.<Project>error(
                        new ProjectAlreadyExistsException("Project with key '" + projectKey + "' already exists")
                ))
                .switchIfEmpty(Mono.defer(() -> {
                    Project project = Project.builder()
                            .projectKey(projectKey)
                            .name(name)
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
                            .eventType("PROJECT_CREATED")
                            .payload(eventPayload)
                            .attempts(0)
                            .createdAt(Instant.now())
                            .build();

                    return projectMemberRepository.save(adminMember)
                            .then(projectSettingRepository.save(defaultSettings))
                            .then(outboxEventRepository.save(outboxEvent))
                            .then(Mono.defer(() -> {
                                log.info("Project successfully created. Related member, settings, and outbox event persisted. Key: {}", projectKey);
                                return Mono.just(savedProject);
                            }));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<Project> getProject(String projectIdStr) {
        if (projectIdStr == null || projectIdStr.isBlank()) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Project ID is required").asRuntimeException());
        }

        UUID projectId;
        try {
            projectId = UUID.fromString(projectIdStr);
        } catch (IllegalArgumentException e) {
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Invalid Project UUID format").asRuntimeException());
        }

        return projectRepository.findById(projectId)
                .switchIfEmpty(Mono.error(Status.NOT_FOUND.withDescription("Project not found").asRuntimeException()));
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<Project> listMyProjects(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) {
            return Flux.error(Status.INVALID_ARGUMENT.withDescription("User ID is required").asRuntimeException());
        }

        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            return Flux.error(Status.INVALID_ARGUMENT.withDescription("Invalid User UUID format").asRuntimeException());
        }

        return projectRepository.findAllByMemberUserId(userId);
    }
}