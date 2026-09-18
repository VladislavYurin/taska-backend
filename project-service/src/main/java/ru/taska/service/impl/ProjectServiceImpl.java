package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.ProjectSetting;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectContextDto;
import ru.taska.domain.dto.projectContext.ProjectLabelDto;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.ProjectService;
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;
import ru.taska.transport.grpc.client.GrpcWorkFlowServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSettingRepository projectSettingRepository;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;
    private final ProjectMemberService projectMemberService;
    private final GrpcIssueServiceClient issueServiceClient;
    private final GrpcWorkFlowServiceClient workflowServiceClient;

    @Override
    @Transactional
    public Mono<Project> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId,
                                       Optional<String> description, Optional<String> color) {
        return projectRepository.findByProjectKey(projectKey)
                .flatMap(p -> {
                    log.info("[{}][{}] Project already exists: projectKey={}",
                            requestId, nodeId, projectKey);
                    return Mono.<Project>error(new DomainException(DomainStatus.ALREADY_EXISTS, "Project with key " + projectKey + " already exists"));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Project project = Project.builder()
                                             .projectKey(projectKey)
                                             .name(projectName)
                                             .createdBy(userId)
                                             .description(description.orElse(null))
                                             .color(color.orElse(null))
                                             .build();

                    return projectRepository.save(project)

                            .flatMap(savedProject ->
                                    projectMemberRepository.save(createAdminMember(savedProject))
                                            .then(projectSettingRepository.insertSetting(
                                                    savedProject.getId(),
                                                    createDefaultProjectSettingsJson(savedProject).toString(),
                                                    Instant.now(),
                                                    savedProject.getCreatedBy()
                                            ))
                                            .then(outboxEventService.saveProjectCreated(requestId, nodeId, savedProject))
                                            .then(Mono.fromRunnable(() ->
                                                    log.info("[{}][{}] Project successfully created: projectKey={}, projectName={}, userId={}",
                                                            requestId, nodeId, projectKey, projectName, userId)))
                                            .thenReturn(savedProject));
                }));
    }

    @Override
    public Mono<ProjectCheckMembershipDto> getProject(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId
    ) {
        return projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId,actorUserId)
                .switchIfEmpty(
                        Mono.defer(() -> {
                            log.warn("[{}][{}] Project not found: {}", requestId, nodeId, projectId);
                            return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                        })
                )
                .flatMap(dto->{
                    if(dto.user_id()==null){
                        log.warn("[{}][{}] User {} is not a member of project {}", requestId, nodeId, actorUserId, projectId);
                        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "You don't have access to this project"));
                    }
                    log.info("[{}][{}] Successfully getting project with id: {}", requestId, nodeId, projectId);
                    return Mono.just(dto);
                });
    }

    /**
     * Доступ к контексту — то же правило, что и у {@link #getProject}: не участник проекта
     * получает PERMISSION_DENIED независимо от глобальной роли. Полноценное чтение контекста
     * для GLOBAL_ADMIN вне проекта (роль в gRPC-заголовке) — отдельная задача.
     * Отказ любого из источников (members/labels/workflows) пробрасывается наружу,
     * а не маскируется пустым списком.
     */
    @Override
    public Mono<ProjectContextDto> getProjectContext(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            GlobalRole globalRole
    ) {
        return projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Project not found: {}", requestId, nodeId, projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                }))
                .flatMap(projectDto -> {
                    if (projectDto.user_id() == null) {
                        log.warn("[{}][{}] User {} is not a member of project {} (globalRole={})",
                                requestId, nodeId, actorUserId, projectId, globalRole);
                        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                                "You don't have access to this project"));
                    }

                    log.info("[{}][{}] Assembling project context: projectId={}", requestId, nodeId, projectId);

                    Mono<List<ProjectMemberDetailsDto>> membersMono =
                            projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId)
                                    .collectList();

                    Mono<List<ProjectLabelDto>> labelsMono =
                            issueServiceClient.listProjectLabels(projectId, actorUserId, requestId, nodeId);

                    Mono<List<ProjectWorkflowDto>> workflowsMono =
                            loadProjectWorkflows(requestId, nodeId, projectId, actorUserId);

                    return Mono.zip(labelsMono, workflowsMono, membersMono)
                            .map(tuple -> new ProjectContextDto(
                                    projectDto,
                                    tuple.getT1(),   // labels
                                    tuple.getT2(),   // workflows
                                    tuple.getT3()    // members
                            ));
                });
    }


    @Override
    public Flux<ProjectCheckMembershipDto> listMyProjects(
            String requestId,
            String nodeId,
            UUID userId
    ) {
        return projectRepository.findAllByMemberUserId(userId)
                .doOnComplete(() -> log.info("[{}][{}] Successfully getting all projects for user id: {}", requestId, nodeId, userId));
    }

    @Override
    public Mono<String> getProjectKeyByIdInternal(UUID projectId) {
        return projectRepository.findProjectKeyById(projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Project not found: {}", projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                }))
                .doOnSuccess(key ->
                        log.debug("Successfully getting project key: {} for projectId: {}", key, projectId)
                );
    }

    @Override
    public Mono<Map<UUID, ProjectInfo>> getProjectInfoByIds(List<UUID> projectIds) {
        Set<UUID> uniqueIds = extractUniqueIds(projectIds);

        if (uniqueIds.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        return projectRepository.findProjectInfoByIds(uniqueIds)
                .collectMap(
                        ProjectInfo::id,
                        Function.identity()
                )
                .doOnSuccess(map -> log.debug("Fetched {}/{} project projections from DB", map.size(), uniqueIds.size()));
    }

    private Set<UUID> extractUniqueIds(List<UUID> projectIds) {
        if (projectIds == null) {
            return Collections.emptySet();
        }

        return projectIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Формирует JSON-содержимое дефолтных настроек проекта.
     *
     * <p>Возвращает именно {@link JsonNode}, а не {@link ProjectSetting}:
     * запись в БД идёт явным INSERT'ом через
     * {@link ProjectSettingRepository#insertSetting}, поэтому промежуточная
     * сущность не нужна.
     */
    private JsonNode createDefaultProjectSettingsJson(Project project) {
        ObjectNode defaultSettingsJson = objectMapper.createObjectNode();
        ArrayNode allowedTypes = defaultSettingsJson.putArray("allowedIssueTypes");
        allowedTypes.add("TASK");
        allowedTypes.add("BUG");
        allowedTypes.add("STORY");
        defaultSettingsJson.put("defaultIssueType", "TASK");

        return defaultSettingsJson;
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

    private Mono<List<ProjectWorkflowDto>> loadProjectWorkflows(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId
    ) {
        return projectSettingRepository.findById(projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Setting not found for project : {}", projectId);
                    return Mono.empty();
                }))
                .flatMapMany(setting -> {
                    JsonNode allowed = setting.getSettings().path("allowedIssueTypes");
                    if (!allowed.isArray() || allowed.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.fromIterable(allowed).map(JsonNode::asString);
                })
                .flatMap(issueType ->
                        workflowServiceClient.getWorkflowForProject(
                                projectId, issueType, actorUserId, requestId, nodeId
                        )
                )
                .collectList();
    }

    @Override
    @Transactional
    public Mono<Project> updateProject(String requestId, String nodeId, UUID projectId, UUID actorUserId,
                                       Optional<String> name, Optional<String> description, boolean clearDescription,
                                       Optional<String> color, boolean clearColor) {
        return projectRepository.findById(projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Project not found: {}", requestId, nodeId, projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                }))
                .flatMap(project -> projectMemberRepository.findByUserIdAndProjectId(actorUserId, projectId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] User {} is not a member of project {}", requestId, nodeId, actorUserId, projectId);
                            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "You don't have access to this project"));
                        }))
                        .flatMap(member -> {
                            if (member.getRole() != ProjectRole.ADMIN) {
                                log.warn("[{}][{}] User {} is not ADMIN of project {}", requestId, nodeId, actorUserId, projectId);
                                return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Only project ADMIN can update the project"));
                            }

                            if (name.isEmpty() && description.isEmpty() && !clearDescription
                                    && color.isEmpty() && !clearColor) {
                                log.info("[{}][{}] Empty PATCH for project {}, nothing to update", requestId, nodeId, projectId);
                                return Mono.just(project);
                            }

                            String newDescription = clearDescription ? null : description.orElse(project.getDescription());
                            String newColor = clearColor ? null : color.orElse(project.getColor());

                            Project updatedProject = project.toBuilder()
                                    .name(name.orElse(project.getName()))
                                    .description(newDescription)
                                    .color(newColor)
                                    .build();

                            return projectRepository.save(updatedProject)
                                    .onErrorMap(OptimisticLockingFailureException.class, ex -> {
                                        log.warn("[{}][{}] Project was concurrently modified: projectId={}", requestId, nodeId, projectId);
                                        return new DomainException(DomainStatus.ABORTED,
                                                "Project was concurrently modified by another request, please retry");
                                    })
                                    .flatMap(savedProject ->
                                            outboxEventService.saveProjectUpdated(requestId, nodeId, savedProject, actorUserId)
                                                    .thenReturn(savedProject));
                        }))
                .doOnSuccess(p -> log.info("[{}][{}] Project successfully updated: projectId={}", requestId, nodeId, projectId));
    }
}
