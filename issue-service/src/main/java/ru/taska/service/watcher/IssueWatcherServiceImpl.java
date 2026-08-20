package ru.taska.service.watcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.PageResult;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.IssueWatchStateDto;
import ru.taska.domain.dto.UnwatchIssueResult;
import ru.taska.domain.dto.WatchIssueResult;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.IssueWatcherService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.Set;
import java.util.UUID;

/**
 * Оркестратор для работы с подписчиками задачи, реализующий {@link IssueWatcherService}.
 * Проверки и сетевые обращения выполняются вне транзакции,
 * запись в БД делегируется в {@link IssueWatcherExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueWatcherServiceImpl implements IssueWatcherService {

    private final IssueRepository issueRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssueProperties issueProperties;
    private final IssueWatcherRepository issueWatcherRepository;
    private final IssueWatcherExecutor executor;

    @Override
    public Mono<WatchIssueResult> watchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            UUID targetUserId
    ) {
        UUID watcherUserId = resolveWatcherUserId(actorUserId, targetUserId);

        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue ->
                        checkMutationRole(requestId, nodeId, issue.getProjectId(), actorUserId, watcherUserId)
                                .thenReturn(issue)
                )
                .flatMap(issue -> Mono.defer(() -> executor.executeWatch(
                        requestId, nodeId, issueId, issue.getProjectId(), watcherUserId, actorUserId
                )))
                .flatMap(watcher -> issueWatcherRepository.countByIssueId(issueId)
                        .map(count -> new WatchIssueResult(watcher, count))
                );
    }
    @Override
    public Mono<UnwatchIssueResult> unwatchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            UUID targetUserId
    ) {
        UUID watcherUserId = resolveWatcherUserId(actorUserId, targetUserId);

        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue ->
                        checkMutationRole(requestId, nodeId, issue.getProjectId(), actorUserId, watcherUserId)
                                .thenReturn(issue)
                )
                .flatMap(issue -> Mono.defer(() -> executor.executeUnwatch(
                        requestId, nodeId, issueId, issue.getProjectId(), watcherUserId, actorUserId
                )))
                .flatMap(removed -> issueWatcherRepository.countByIssueId(issueId)
                        .map(count -> new UnwatchIssueResult(removed, count))
                );
    }

    @Override
    public Mono<PageResult<IssueWatcher>> listIssueWatchers(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            Integer page,
            Integer pageSize
    ) {
        int resolvedPage = issueProperties.list().resolvePage(page);
        int resolvedPageSize = issueProperties.list().resolvePageSize(pageSize);
        long offset = (long) resolvedPage * resolvedPageSize;

        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listWatchersRoles();

                    return projectRoleChecker
                            .checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .then(Mono.defer(() -> Mono.zip(
                                    issueWatcherRepository.countByIssueId(issueId),
                                    issueWatcherRepository.findAllByIssueId(issueId)
                                            .skip(offset)
                                            .take(resolvedPageSize)
                                            .collectList()
                            )));
                })
                .map(t -> new PageResult<>(t.getT2(), t.getT1()))
                .doOnSuccess(result ->
                        log.debug("[{}][{}] Watchers page found: issueId={}, page={}, size={}, total={}",
                                requestId, nodeId, issueId, resolvedPage, resolvedPageSize,
                                result != null ? result.totalCount() : null)
                );
    }

    @Override
    public Mono<IssueWatchStateDto> getIssueWatchState(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listWatchersRoles();

                    return projectRoleChecker
                            .checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles);
                })
                .then(Mono.zip(
                        issueWatcherRepository.existsByIssueIdAndUserId(issueId, actorUserId),
                        issueWatcherRepository.countByIssueId(issueId)
                ))
                .map(tuple -> new IssueWatchStateDto(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<IssueWatchStateDto> getWatchState(UUID issueId, UUID actorUserId) {
        return Mono.zip(
                        issueWatcherRepository.existsByIssueIdAndUserId(issueId, actorUserId),
                        issueWatcherRepository.countByIssueId(issueId)
                )
                .map(tuple -> new IssueWatchStateDto(tuple.getT1(), tuple.getT2()));
    }

    private UUID resolveWatcherUserId(UUID actorUserId, UUID targetUserId) {
        return targetUserId == null ? actorUserId : targetUserId;
    }

    private Mono<Void> checkMutationRole(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            UUID watcherUserId
    ) {
        Set<ProjectRole> allowedRoles = actorUserId.equals(watcherUserId)
                ? issueProperties.allowedRoles().watchIssueRoles()
                : issueProperties.allowedRoles().manageWatchersRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles);
    }

    private Mono<Issue> findActiveIssue(String requestId, String nodeId, UUID issueId) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue not found: id={}", requestId, nodeId, issueId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }));
    }
}
