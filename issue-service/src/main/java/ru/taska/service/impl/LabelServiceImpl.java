package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.LabelMapper;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.LabelService;
import ru.taska.service.OutboxEventService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabelServiceImpl implements LabelService {

    private final IssueProperties issueProperties;
    private final ProjectLabelsRepository projectLabelsRepository;
    private final IssueLabelsRepository issueLabelsRepository;
    private final IssueRepository issueRepository;
    private final LabelMapper mapper;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    /**
     * Возвращает DTO createProjectLabel
     */
    @Override
    @Transactional
    public Mono<LabelResponses.ProjectLabelInfo> createProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.CreateProjectLabelRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().createProjectLabelRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, requestDto.projectId(), requestDto.actorUserId(), allowedRoles)

                .then(Mono.defer(() -> validateLabelNameUniqueness(requestDto.projectId(), requestDto.name())))
                .then(Mono.defer(() -> {
                    ProjectLabels label = mapper.toEntity(requestDto);
                    return projectLabelsRepository.save(label);
                }))

                .map(mapper::toProjectLabelInfo)
                .doOnSuccess(LabelInfo ->
                        log.debug("Created project label: id={}, projectId={}, name={}", LabelInfo.id(), LabelInfo.projectId(), LabelInfo.name())
                );

    }

    /**
     * Возвращает DTO updateProjectLabel
     */
    @Override
    @Transactional
    public Mono<LabelResponses.ProjectLabelInfo> updateProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.UpdateProjectLabelRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().updateProjectLabelRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, requestDto.projectId(), requestDto.actorUserId(), allowedRoles)
                .then(projectLabelsRepository.findByIdAndDeletedAtIsNull(requestDto.labelId()))
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND, "Label not found:" + requestDto.labelId()
                )))
                .flatMap(label -> {
                    if (!label.getProjectId().equals(requestDto.projectId())) {
                        return Mono.error(new DomainException(
                                DomainStatus.FAILED_PRECONDITION, "Label does not belong to this project"
                        ));
                    }
                    return validateLabelNameUniquenessForUpdate(requestDto.projectId(), requestDto.name(), requestDto.labelId())
                            .then(Mono.fromCallable(() -> {
                                mapper.updateEntity(label, requestDto);
                                return label;
                            }))
                            .flatMap(projectLabelsRepository::save);
                })
                .map(mapper::toProjectLabelInfo)
                .doOnSuccess(LabelInfo ->
                        log.debug("Updated project label: id={}, name={}", LabelInfo.id(), LabelInfo.name())
                );
    }

    /**
     * Возвращает DTO deleteProjectLabel
     */
    @Override
    @Transactional
    public Mono<LabelResponses.DeleteProjectLabelResponseDto> deleteProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.DeleteProjectLabelRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().deleteProjectLabelRoles();

        return projectRoleChecker.checkProjectRole(
                        requestId, nodeId, requestDto.projectId(), requestDto.actorUserId(), allowedRoles)
                .then(projectLabelsRepository.findByIdAndDeletedAtIsNull(requestDto.labelId())
                        .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND, "Label not found:" + requestDto.labelId()
                        )))
                        .flatMap(label -> {
                            if (!label.getProjectId().equals(requestDto.projectId())) {
                                return Mono.error(new DomainException(
                                        DomainStatus.FAILED_PRECONDITION, "Label does not belong to this project")
                                );
                            }
                            return projectLabelsRepository.softDelete(requestDto.labelId())
                                    .thenReturn(LabelResponses.DeleteProjectLabelResponseDto.of(
                                            requestDto.labelId(), requestDto.projectId()
                                    ));
                        })
                )
                .doOnSuccess(dto ->
                        log.debug("Deleted project label: id={}, projectId={}", dto.labelId(), dto.projectId())
                );
    }

    /**
     * Возвращает DTO listProjectLabels
     */
    @Override
    public Mono<LabelResponses.ListProjectLabelResponseDto> listProjectLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListProjectLabelsRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listProjectLabelRoles();

        return projectRoleChecker.checkProjectRole(
                        requestId, nodeId, requestDto.projectId(), requestDto.actorUserId(), allowedRoles)
                .then(projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(requestDto.projectId())
                        .collectList()
                        .map(mapper::toListProjectLabelResponseDto)
                )
                .doOnSuccess(dto ->
                        log.debug("[{}][{}] Found {} labels for project: {}", requestId, nodeId, dto.totalCount(), requestDto.projectId())
                );

    }

    /**
     * Возвращает DTO addIssueLabel
     */
    @Override
    @Transactional
    public Mono<LabelResponses.AddIssueLabelResponseDto> addIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.AddIssueLabelRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().addIssueLabelRoles();

        return issueRepository.findActiveById(requestDto.issueId())
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND, "Issue not found: " + requestDto.issueId()
                )))
                .flatMap(issue ->
                        projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), requestDto.actorUserId(), allowedRoles)
                                .then(projectLabelsRepository.findByIdAndDeletedAtIsNull(requestDto.labelId())
                                        .switchIfEmpty(Mono.error(new DomainException(
                                                DomainStatus.NOT_FOUND, "Label not found: " + requestDto.labelId()
                                        )))
                                        .flatMap(label -> {
                                            if (!label.getProjectId().equals(issue.getProjectId())) {
                                                return Mono.error(new DomainException(
                                                        DomainStatus.FAILED_PRECONDITION, "Label does not belong to issue's project"
                                                ));
                                            }
                                            return issueLabelsRepository.existsByIssueIdAndLabelId(requestDto.issueId(), requestDto.labelId())
                                                    .flatMap(exists -> {
                                                        if (exists) {
                                                            return Mono.error(new DomainException(
                                                                    DomainStatus.ALREADY_EXISTS, "Label already added to this issue"
                                                            ));
                                                        }
                                                        IssueLabels issueLabels = mapper.toEntity(requestDto);
                                                        return issueLabelsRepository.save(issueLabels)
                                                                .then(Mono.defer(() -> {
                                                                    JsonNode payload = payloadSerializer.createLabelAddedPayload(label, requestDto.issueId(),requestDto.actorUserId());
                                                                    return issueHistoryService.saveIssueHistory(
                                                                                    requestId, nodeId, requestDto.issueId(), requestDto.actorUserId(), IssueEventType.LABEL_ADDED, payload)
                                                                            .then(outboxEventService.saveOutboxEvent(
                                                                                    requestId, nodeId, AggregateType.ISSUE, requestDto.issueId(), EventType.ISSUE_LABEL_ADDED, payload)
                                                                            )

                                                                            .thenReturn(LabelResponses.AddIssueLabelResponseDto
                                                                                    .of(requestDto.issueId(), requestDto.labelId(), requestDto.actorUserId())
                                                                            );
                                                                }));
                                                    });
                                        })
                                )
                )
                .doOnSuccess(dto ->
                        log.debug("Added label {} to issue {}", dto.labelId(), dto.issueId())
                );
    }

    /**
     * Возвращает DTO removeIssueLabel
     */
    @Override
    @Transactional
    public Mono<LabelResponses.RemoveIssueLabelResponseDto> removeIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.RemoveIssueLabelRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().removeIssueLabelRoles();

        return issueRepository.findActiveById(requestDto.issueId())
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND, "Issue not found: " + requestDto.issueId()
                )))
                .flatMap(issue ->
                        projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), requestDto.actorUserId(), allowedRoles)
                                .then(projectLabelsRepository.findByIdAndDeletedAtIsNull(requestDto.labelId()))
                                .switchIfEmpty(Mono.error(new DomainException(
                                        DomainStatus.NOT_FOUND, "Label not found: " + requestDto.labelId()
                                )))
                                .flatMap(label -> {
                                    if (!label.getProjectId().equals(issue.getProjectId())) {
                                        return Mono.error(new DomainException(
                                                DomainStatus.FAILED_PRECONDITION, "Label does not belong to issue's project"
                                        ));
                                    }
                                    return issueLabelsRepository.existsByIssueIdAndLabelId(requestDto.issueId(), requestDto.labelId())
                                            .flatMap(exist -> {
                                                if (!exist) {
                                                    return Mono.error(new DomainException(
                                                            DomainStatus.NOT_FOUND, "Label not attached to this issue"
                                                    ));
                                                }
                                                return issueLabelsRepository.deleteByIssueIdAndLabelId(requestDto.issueId(), requestDto.labelId())
                                                        .then(Mono.defer(() -> {
                                                            JsonNode payload = payloadSerializer.createLabelRemovedPayload(label, requestDto.issueId(),requestDto.actorUserId());
                                                            return issueHistoryService.saveIssueHistory(
                                                                            requestId, nodeId, requestDto.issueId(), requestDto.actorUserId(), IssueEventType.LABEL_REMOVED, payload)
                                                                    .then(outboxEventService.saveOutboxEvent(
                                                                            requestId, nodeId, AggregateType.ISSUE, requestDto.issueId(), EventType.ISSUE_LABEL_REMOVED, payload)
                                                                    )
                                                                    .thenReturn(LabelResponses.RemoveIssueLabelResponseDto.of(requestDto.issueId(), requestDto.labelId()));
                                                        }));
                                            });

                                })
                )
                .doOnSuccess(dto ->
                        log.debug("Removed label {} from issue {}", dto.labelId(), dto.issueId())
                );
    }

    /**
     * Возвращает DTO listIssueLabels
     */
    @Override
    public Mono<LabelResponses.ListIssueLabelResponseDto> listIssueLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListIssueLabelsRequestDto requestDto
    ) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listIssueLabelRoles();

        return issueRepository.findActiveById(requestDto.issueId())
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND, "Issue not found: " + requestDto.issueId()
                )))
                .flatMap(issue ->
                        projectRoleChecker.checkProjectRole(
                                        requestId, nodeId, issue.getProjectId(), requestDto.actorUserId(), allowedRoles)
                                .then(issueLabelsRepository.findActiveLabelsByIssueId(requestDto.issueId())
                                        .collectList()
                                        .map(mapper::toListIssueLabelResponseDto)
                                )
                )
                .doOnSuccess(dto ->
                        log.debug("Found {} labels for issue: {}", dto.labels().size(), requestDto.issueId())
                );
    }

    /**
     * Проверка перед созданием метки (новая метка должна быть с уникальным именем)
     *
     * @param projectId - Id проекта
     * @param name      - название метки
     * @return Mono.empty() в случае успешной проверки, иначе DomainStatus.ALREADY_EXISTS
     */
    private Mono<Void> validateLabelNameUniqueness(UUID projectId, String name) {
        String trimmedLoweredName = name.trim().toLowerCase();

        return projectLabelsRepository.existsActiveByName(projectId, trimmedLoweredName)

                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DomainException(
                                DomainStatus.ALREADY_EXISTS,
                                "Label with name '" + name + "' already exists in this project"
                        ));
                    }
                    return Mono.empty();
                });
    }

    /**
     * Проверка перед обновлением метки (обновляемая метка должна иметь незанятое название -> id обновляемой метки должно совпадать с id метки, найденному по переданному имени)
     *
     * @param projectId - Id проекта
     * @param name - название метки
     * @param excludeLabelId - Id метки
     * @return Mono.empty() в случае успешной проверки, иначе DomainStatus.ALREADY_EXISTS
     */
    private Mono<Void> validateLabelNameUniquenessForUpdate(UUID projectId, String name, UUID excludeLabelId) {
        String trimmedLoweredName = name.trim().toLowerCase();
        return projectLabelsRepository.findActiveByName(projectId, trimmedLoweredName)
                .flatMap(existing -> {
                    if (!existing.getId().equals(excludeLabelId)) {
                        return Mono.error(new DomainException(
                                DomainStatus.ALREADY_EXISTS,
                                "Label with name '" + name + "' already exists in this project"
                        ));
                    }
                    return Mono.empty();
                });
    }

}
