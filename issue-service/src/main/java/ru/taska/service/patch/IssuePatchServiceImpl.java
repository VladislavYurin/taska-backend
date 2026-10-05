package ru.taska.service.patch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.PatchIssueResult;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssuePatchServiceImpl implements IssuePatchService {

    private final IssueProperties issueProperties;
    private final IssueRepository issueRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssuePatchExecutor issuePatchExecutor;

    @Override
    public Mono<PatchIssueResult> patchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            int ifMatchVersion,
            IssuePatch patch
    ) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: {} was not found", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " was not found"));
                }))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().updateIssueRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(issue);
                })
                .flatMap(issue -> {
                    if (issue.getVersion() != ifMatchVersion) {
                        log.info("[{}][{}] Issue with id: {} version conflict: current version={}, If-Match version={}",
                                requestId, nodeId, issueId, issue.getVersion(), ifMatchVersion);
                        return Mono.just(new PatchIssueResult(issue, true));
                    }

                    Issue patched = patch.applyTo(issue);

                    return validateStartNotAfterDue(requestId, nodeId, patched.getStartDate(), patched.getDueDate())
                            .then(Mono.defer(() -> checkPatchAssigneeRoles(requestId, nodeId, issue, patched, actorUserId)))
                            .then(Mono.defer(() -> issuePatchExecutor.executePatch(
                                    requestId, nodeId, issueId, actorUserId, ifMatchVersion, patch)));
                });
    }

    /**
     * Проверяет, что дата начала не позже даты окончания. Если хотя бы одна из дат не задана — проверка пропускается.
     */
    private Mono<Void> validateStartNotAfterDue(String requestId, String nodeId, LocalDate startDate, LocalDate dueDate) {
        if (startDate != null && dueDate != null && startDate.isAfter(dueDate)) {
            log.warn("[{}][{}] Start date: [{}] must not be after Due date: [{}]",
                    requestId, nodeId, startDate, dueDate);
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Start date: " + startDate + " must not be after Due date: " + dueDate));
        }
        return Mono.empty();
    }

    /**
     * При смене исполнителя через PATCH проверяет права так же, как при назначении задачи:
     * инициатор и новый исполнитель должны иметь роль из {@code assignIssueRoles}.
     * Снятие исполнителя проверяет только права инициатора.
     */
    private Mono<Void> checkPatchAssigneeRoles(String requestId, String nodeId, Issue issue, Issue patched, UUID actorUserId) {
        UUID newAssigneeId = patched.getAssigneeId();
        if (Objects.equals(newAssigneeId, issue.getAssigneeId())) {
            return Mono.empty();
        }

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().assignIssueRoles();
        Mono<Void> actorCheck = projectRoleChecker.checkProjectRole(
                requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles);
        Mono<Void> assigneeCheck = newAssigneeId == null || newAssigneeId.equals(actorUserId)
                ? Mono.empty()
                : projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), newAssigneeId, allowedRoles);

        return actorCheck.then(assigneeCheck);
    }
}
