package ru.taska.service.watcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.AutoWatchRole;
import ru.taska.domain.Issue;

import java.util.UUID;

/**
 * Автоподписка reporter/assignee на задачу.
 * <p>
 * Подписка идемпотентна ({@link IssueWatcherExecutor#executeWatch} —
 * {@code ON CONFLICT DO NOTHING}). Ошибки БД/outbox пробрасываются наружу,
 * чтобы create/assign откатились в той же транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueAutoWatchService {

    private final IssueProperties issueProperties;
    private final IssueWatcherExecutor issueWatcherExecutor;

    /**
     * Подписывает reporter при создании задачи, если включено в конфиге.
     */
    public Mono<Void> watchReporterOnCreate(String requestId, String nodeId, Issue issue) {
        return autoWatchIfEnabled(
                issueProperties.autoWatch().onCreateReporter(),
                requestId,
                nodeId,
                issue,
                issue.getReporterId(),
                issue.getReporterId(),
                AutoWatchRole.REPORTER
        );
    }

    /**
     * Подписывает assignee при назначении, если включено в конфиге.
     */
    public Mono<Void> watchAssigneeOnAssign(
            String requestId,
            String nodeId,
            Issue issue,
            UUID actorUserId
    ) {
        return autoWatchIfEnabled(
                issueProperties.autoWatch().onAssignAssignee(),
                requestId,
                nodeId,
                issue,
                issue.getAssigneeId(),
                actorUserId,
                AutoWatchRole.ASSIGNEE
        );
    }

    private Mono<Void> autoWatchIfEnabled(
            boolean enabled,
            String requestId,
            String nodeId,
            Issue issue,
            UUID watcherUserId,
            UUID actorUserId,
            AutoWatchRole role
    ) {
        if (!enabled || watcherUserId == null) {
            return Mono.empty();
        }

        return issueWatcherExecutor.executeWatch(
                        requestId,
                        nodeId,
                        issue.getId(),
                        issue.getProjectId(),
                        watcherUserId,
                        actorUserId
                )
                .doOnSuccess(__ -> log.debug(
                        "[{}][{}] Auto-watched {}: issueId={}, userId={}",
                        requestId, nodeId, role, issue.getId(), watcherUserId
                ))
                .then();
    }
}
