package ru.taska.service.worklog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Реализация {@link WorklogService}: валидирует вход, проверяет наличие задачи
 * и права пользователя, затем делегирует изменения в {@link WorklogExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklogServiceImpl implements WorklogService {
    private final WorklogRepository worklogRepository;
    private final IssueRepository issueRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssueProperties issueProperties;
    private final WorklogExecutor worklogExecutor;

    @Override
    public Mono<Worklog> addIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            CreateWorklogDto dto
    ) {
        return validateCreateDto(dto)
                .then(findActiveIssue(requestId, nodeId, issueId))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().addWorklogRoles();
                    return projectRoleChecker.checkProjectRole(
                            requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles
                    );

                })
                .then(Mono.defer(() ->
                        worklogExecutor.executeAdd(requestId, nodeId, issueId, actorUserId, dto)
                ));
    }

    @Override
    public Mono<Worklog> updateIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId,
            UpdateWorklogDto worklogDto
    ) {
        return validateUpdateDto(worklogDto)
                .then(findActiveIssue(requestId, nodeId, issueId))
                .flatMap(issue -> findActiveWorklog(requestId, nodeId, worklogId)
                        .flatMap(worklog -> {

                            Set<ProjectRole> allowedRoles = actorUserId.equals(worklog.getAuthorUserId()) ?
                                    issueProperties.allowedRoles().updateWorklogRoles() :
                                    issueProperties.allowedRoles().manageWorklogRoles();

                            return validateWorklogBelongsToIssue(requestId, nodeId, issueId, worklog)
                                    .then(projectRoleChecker.checkProjectRole(
                                            requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles));
                        })
                        .then(Mono.defer(() ->
                                worklogExecutor.executeUpdate(requestId, nodeId, issueId, worklogId, actorUserId, worklogDto)
                        )));
    }

    @Override
    public Mono<List<Worklog>> listIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    var allowedRoles = issueProperties.allowedRoles().listWorklogRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .then(worklogRepository.findActiveByIssueId(issue.getId()).collectList());
                });
    }

    @Override
    public Mono<Worklog> deleteIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId
    ) {
        return findActiveWorklog(requestId, nodeId, worklogId)
                .flatMap(worklog -> {

                    var allowedRoles = actorUserId.equals(worklog.getAuthorUserId()) ?
                            issueProperties.allowedRoles().deleteWorklogRoles() :
                            issueProperties.allowedRoles().manageWorklogRoles();

                    return validateWorklogBelongsToIssue(requestId, nodeId, issueId,  worklog)
                            .then(projectRoleChecker.checkProjectRole(requestId, nodeId, worklog.getProjectId(), actorUserId, allowedRoles)
                            .then(worklogExecutor.executeDelete(requestId, nodeId, issueId, worklogId, actorUserId)));
                });
    }

    /** Проверяет, что запись относится к указанной задаче, иначе {@code NOT_FOUND}. */
    private Mono<Void> validateWorklogBelongsToIssue(String requestId, String nodeId, UUID issueId, Worklog worklog) {
        if (!worklog.getIssueId().equals(issueId)) {
            log.warn("[{}][{}] Issue {} doesnt belongs to worklog {}", requestId, nodeId, issueId, worklog.getId());

            return Mono.error(new DomainException(
                    DomainStatus.NOT_FOUND,
                    "Issue doesnt belongs to worklog"
            ));
        }
        return Mono.empty();
    }

    /** Ищет неудалённую запись по id, иначе {@code NOT_FOUND}. */
    private Mono<Worklog> findActiveWorklog(String requestId, String nodeId, UUID worklogId) {
        return worklogRepository.findActiveById(worklogId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Worklog with id: {} was not found", requestId, nodeId, worklogId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Worklog with id: " + worklogId + " not found"
                    ));
                }));
    }

    /** Ищет неудалённую задачу по id, иначе {@code NOT_FOUND}. */
    private Mono<Issue> findActiveIssue(String requestId, String nodeId, UUID issueId) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: {} was not found", requestId, nodeId, issueId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " not found"
                    ));
                }));
    }

    /** Проверяет поля при обновлении: заданные значения должны быть корректными. */
    private Mono<Void> validateUpdateDto(UpdateWorklogDto dto) {
        if (dto.spentMinutes() == null && dto.workDate() == null && dto.comment() == null) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "No data provided for update"
            ));
        }
        if (dto.spentMinutes() != null && dto.spentMinutes() <= 0) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "SpentMinutes must be greater than 0"
            ));
        }
        int maxFutureDays = issueProperties.maxFutureDays();
        if (dto.workDate() != null && dto.workDate().isAfter(LocalDate.now().plusDays(maxFutureDays))) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "WorkDate is too far in the future"
            ));
        }
        return Mono.empty();
    }

    /** Проверяет поля при создании: {@code spentMinutes} и {@code workDate} обязательны. */
    private Mono<Void> validateCreateDto(CreateWorklogDto dto) {
        if (dto.spentMinutes() == null || dto.spentMinutes() <= 0) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "SpentMinutes must be greater than 0"
            ));
        }

        if (dto.workDate() == null) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "WorkDate must be not null"
            ));
        }

        int maxFutureDays = issueProperties.maxFutureDays();
        if (dto.workDate().isAfter(LocalDate.now().plusDays(maxFutureDays))) {
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "WorkDate is too far in the future"
            ));
        }

        return Mono.empty();
    }
}
