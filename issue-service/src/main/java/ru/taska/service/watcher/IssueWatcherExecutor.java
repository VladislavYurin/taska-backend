package ru.taska.service.watcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueWatcher;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;

import java.util.UUID;

/**
 * Сервис-исполнитель для транзакционных операций с подписчиками задачи.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueWatcherExecutor {

    private final IssueWatcherRepository issueWatcherRepository;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    /**
     * Атомарно и идемпотентно создаёт подписку и outbox-событие.
     * Событие пишется только при фактическом создании; повторный watch
     * ({@code ON CONFLICT DO NOTHING}) возвращает существующую запись без ошибки.
     */
    @Transactional
    public Mono<IssueWatcher> executeWatch(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID projectId,
            UUID watcherUserId,
            UUID actorUserId
    ) {
        return issueWatcherRepository.insertIfAbsent(issueId, projectId, watcherUserId, actorUserId)
                .flatMap(watcher -> {
                    var payload = payloadSerializer.createIssueWatchedPayload(
                            issueId, projectId, watcherUserId, actorUserId
                    );

                    return outboxEventService.saveOutboxEvent(
                                    requestId,
                                    nodeId,
                                    AggregateType.ISSUE,
                                    issueId,
                                    EventType.ISSUE_WATCHED,
                                    payload
                            )
                            .doOnSuccess(__ ->
                                    log.debug("[{}][{}] Issue watched: issueId={}, watcherUserId={}",
                                            requestId, nodeId, issueId, watcherUserId)
                            )
                            .thenReturn(watcher);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("[{}][{}] Issue is already watched: issueId={}, watcherUserId={}",
                            requestId, nodeId, issueId, watcherUserId);

                    return issueWatcherRepository.findByIssueIdAndUserId(issueId, watcherUserId);
                }));
    }

    /**
     * Атомарно удаляет подписку и пишет outbox-событие.
     * Если подписки не было — возвращает false без события.
     */
    @Transactional
    public Mono<Boolean> executeUnwatch(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID projectId,
            UUID watcherUserId,
            UUID actorUserId
    ) {
        return issueWatcherRepository.deleteByIssueIdAndUserId(issueId, watcherUserId)
                .defaultIfEmpty(0L)
                .flatMap(deletedCount -> {
                    if (deletedCount == 0) {
                        log.debug("[{}][{}] Issue was not watched: issueId={}, watcherUserId={}",
                                requestId, nodeId, issueId, watcherUserId);

                        return Mono.just(false);
                    }

                    var payload = payloadSerializer.createIssueUnwatchedPayload(
                            issueId, projectId, watcherUserId, actorUserId
                    );

                    return outboxEventService.saveOutboxEvent(
                                    requestId,
                                    nodeId,
                                    AggregateType.ISSUE,
                                    issueId,
                                    EventType.ISSUE_UNWATCHED,
                                    payload
                            )
                            .doOnSuccess(__ ->
                                    log.debug("[{}][{}] Issue unwatched: issueId={}, watcherUserId={}",
                                            requestId, nodeId, issueId, watcherUserId)
                            )
                            .thenReturn(true);
                });
    }
}
