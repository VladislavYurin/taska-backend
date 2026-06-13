package ru.taska.service.impl;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.UsersProjectsResponse;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.ProjectSetting;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.ProjectService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSettingRepository projectSettingRepository;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional
    public Mono<ProjectResponse> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId) {
        return projectRepository.findByProjectKey(projectKey)
                .flatMap(p -> {
                    log.info("[{}][{}] Project already exists: projectKey={}",
                            requestId, nodeId, projectKey);
                    return Mono.<ProjectResponse>error(new DomainException(DomainStatus.ALREADY_EXISTS, "Project with key " + projectKey + " already exists"));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Project project = Project.builder()
                                             .projectKey(projectKey)
                                             .name(projectName)
                                             .createdBy(userId)
                                             .build();

                    return projectRepository.save(project)

                            .flatMap(savedProject -> {
                                return projectMemberRepository.save(createAdminMember(savedProject))
                                        .then(projectSettingRepository.save(createDefaultProjectSettings(savedProject)))
                                        .then(outboxEventService.saveProjectCreated(savedProject))
                                        .then(Mono.fromRunnable(() ->
                                                log.info("[{}][{}] Project successfully created: projectKey={}, projectName={}, userId={}",
                                                        requestId, nodeId, projectKey, projectName, userId)))
                                        .thenReturn(savedProject);
                            })
                .map(projectMapper::toProjectResponse);
                }));
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<ProjectResponse> getProject(String requestId, String nodeId, UUID projectId) {
        return projectRepository.findById(projectId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project with projectId " + projectId + " was not found ")))
                .map(projectMapper::toProjectResponse)
                .doOnSuccess(p -> log.info("[{}][{}] Successfully getting project with id: {}", requestId, nodeId, projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<UsersProjectsResponse> listMyProjects(String requestId, String nodeId, UUID userId) {
        return projectRepository.findAllByMemberUserId(userId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Not found projects for user with id: " + userId)))
                .map(projectMapper::toProjectResponse)
                .collectList()
                .map(p -> {
                    return UsersProjectsResponse.newBuilder()
                            .addAllProjectResponse(p)
                            .build();
                })
                .doOnSuccess(p -> log.info("[{}][{}] Successfully getting all projects for user id: {}", requestId, nodeId, userId));
    }

    private ProjectSetting createDefaultProjectSettings(Project project) {
        ObjectNode defaultSettingsJson = objectMapper.createObjectNode();
        ArrayNode allowedTypes = defaultSettingsJson.putArray("allowedIssueTypes");
        allowedTypes.add("TASK");
        allowedTypes.add("BUG");
        allowedTypes.add("STORY");
        defaultSettingsJson.put("defaultIssueType", "TASK");

        return ProjectSetting.builder()
                .projectId(project.getId())
                .settings(defaultSettingsJson)
                .updatedBy(project.getCreatedBy())
                .updatedAt(Instant.now())
                .build();
    }

    private ProjectMember createAdminMember(Project savedProject) {
        return ProjectMember.builder()
                            .projectId(savedProject.getId())
                            .userId(savedProject.getCreatedBy())
                            .role(ProjectRole.ADMIN)
                            .addedBy(savedProject.getCreatedBy())
                            .addedAt(Instant.now())
                            .build();
    }
}