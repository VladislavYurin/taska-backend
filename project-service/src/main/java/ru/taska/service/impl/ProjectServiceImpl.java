package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public Mono<Project> createProject(
            String requestId,
            String nodeId,
            String projectKey,
            String projectName,
            UUID userId
    ) {
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
                    // Проверка доступа
                    if (projectDto.user_id() == null) {
                        if (globalRole != GlobalRole.GLOBAL_ADMIN) {
                            log.warn("[{}][{}] User {} is not a member of project {}",
                                    requestId, nodeId, actorUserId, projectId);
                            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                                    "You don't have access to this project"));
                        }
                        // GLOBAL_ADMIN без членства — продолжаем.
                        // Сейчас members и labels вернут PERMISSION_DENIED, поэтому каждый
                        // такой вызов обёрнут в onErrorResume -> пустой список.
                        // Когда в project-service и issue-service появится работа с Global Role -> достаточно убрать onErrorResume —
                        // код ниже начнёт наполнять members/labels как обычно.
                    }

                    log.info("[{}][{}] Assembling project context: projectId={}", requestId, nodeId, projectId);

                    ///todo проблема - если global_admin, то его не будет в участниках проекта - будем обходить внедрением UserContext в Header grpc запроса
                    /// todo: временнон решение - заглушка на GLOBAL_ADMIN - отдаем пустые списки, где требуется actor_user_id c предупреждением

                    /// Загружаем участников.
                    // onErrorResume: GLOBAL_ADMIN без членства сейчас получит PERMISSION_DENIED
                    // из ProjectMemberRepository.findProjectMembers (SQL требует EXISTS по actorUserId).
                    // Отдаём пустой список, чтобы не ронять весь контекст.
                    Mono<List<ProjectMemberDetailsDto>> membersMono =
                            projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId)
                                    .collectList()
                                    .onErrorResume(this::isPermissionDenied, e -> {
                                        log.warn("[{}][{}] Access denied while loading project members, " +
                                                        "returning empty list: projectId={}, actorUserId={}",
                                                requestId, nodeId, projectId, actorUserId);
                                        return Mono.just(List.of());
                    });

                    /// Загружаем метки из issue-service.
                    // onErrorResume: симметрично members — issue-service проверяет роль актора
                    // через ProjectRoleChecker и для не-участника отдаёт отказ.
                    Mono<List<ProjectLabelDto>> labelsMono =
                            issueServiceClient.listProjectLabels(projectId, actorUserId, requestId, nodeId)
                                    .onErrorResume(this::isPermissionDenied,e -> {
                                        log.warn("[{}][{}] Failed to load project labels for context, " +
                                                        "returning empty list: projectId={}, actorUserId={}, reason={}",
                                                requestId, nodeId, projectId, actorUserId, e.getMessage());
                                        return Mono.just(List.of());
                                    });


                    /// Загружаем workflows из workflow-service.
                    // Пока workflow-service не проверяет actor_user_id (TAS-207),
                    // этот вызов для GLOBAL_ADMIN проходит как обычно. onErrorResume в качестве заглушки
                    Mono<List<ProjectWorkflowDto>> workflowsMono =
                            loadProjectWorkflows(requestId, nodeId, projectId, actorUserId)
                                    .onErrorResume(this::isPermissionDenied,e -> {
                                        log.warn("[{}][{}] Failed to load project workflows for context, " +
                                                        "returning empty list: projectId={}, actorUserId={}, reason={}",
                                                requestId, nodeId, projectId, actorUserId, e.getMessage());
                                        return Mono.just(List.of());
                                    });

                    return Mono.zip(membersMono, labelsMono, workflowsMono)
                            .map(tuple -> new ProjectContextDto(
                                    projectDto,
                                    tuple.getT2(),   // labels
                                    tuple.getT3(),   // workflows
                                    tuple.getT1()    // members
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

    /**
     * Предикат для onErrorResume: гасим только отказ в доступе.
     * Любая другая ошибка (UNAVAILABLE, NOT_FOUND, ошибка БД) пробрасывается наружу,
     * чтобы не маскировать реальную деградацию сервисов.
     */
    private boolean isPermissionDenied(Throwable e) {
        return e instanceof DomainException de && de.getStatus() == DomainStatus.PERMISSION_DENIED;
    }
}
