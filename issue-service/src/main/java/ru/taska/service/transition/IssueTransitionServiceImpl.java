package ru.taska.service.transition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.dto.IssueWithHistory;
import ru.taska.domain.dto.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.workflow.IssueTransitionValidator;

import java.util.Set;
import java.util.UUID;

/**
 * Оркестратор, реализующий {@link IssueTransitionExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTransitionServiceImpl implements IssueTransitionService {

    private final IssueProperties issueProperties;
    private final IssueRepository issueRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssueTransitionValidator issueTransitionValidator;
    private final IssueTransitionExecutor issueTransitionExecutor;

    /**
     * Оркестрирует процесс перехода задачи по workflow.
     * Проверяет роль пользователя, инициировавшего запрос через {@link ProjectRoleChecker}.
     * Валидирует правила перехода задачи по workflow через {@link IssueTransitionValidator}.
     * В случае успешной валидации передает выполнение перехода задачи по workflow
     * в транзакционный блок {@link IssueTransitionExecutor#executeTransition}.
     * The transaction compares the version whose source status was validated, even without If-Match.
     * A concurrent change returns a conflict instead of replaying an already validated transition.
     */
    @Override
    public Mono<IssueWithHistory> transitionIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID transitionId,
            UUID actorUserId,
            String payload,
            Integer expectedVersion
    ) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] Issue with ID {} was not found", requestId, nodeId, issueId);

                            return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                        })
                )
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().issueTransitionRoles();

                    return projectRoleChecker.checkProjectWriteRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(issue);
                })
                .flatMap(issue -> {
                    // Version/status may be disclosed only after the project ACL check.
                    if (expectedVersion != null && !expectedVersion.equals(issue.getVersion())) {
                        return Mono.error(IssueTransitionExecutor.versionConflict(issue));
                    }
                    return Mono.just(issue);
                })
                .flatMap(issue -> issueTransitionValidator
                        .validateTransition(requestId, nodeId, issue, transitionId, actorUserId, payload)
                        .flatMap(targetStatus -> issueTransitionExecutor.executeTransition(
                                requestId, nodeId, issueId, targetStatus, transitionId, actorUserId,
                                expectedVersion != null ? expectedVersion : issue.getVersion(),payload)));
    }

}
