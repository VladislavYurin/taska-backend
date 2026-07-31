package ru.taska.service.transition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

/**
 * Сервис-исполнитель для атомарных операций с БД в контексте перехода задачи по workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTransitionExecutor {

    private final IssueRepository issueRepository;
    private final IssueHistoryRepository issueHistoryRepository;
    private final IssueProperties issueProperties;
    private final PayloadSerializer  payloadSerializer;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;

    /**
     * Транзакционное выполнение изменения статуса задачи.
     */
    @Transactional
    public Mono<IssueWithHistory> executeTransition(
            String requestId,
            String nodeId,
            UUID issueId,
            String targetStatusKey,
            UUID transitionId,
            UUID actorUserId
    ) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with ID {} was not found", requestId, nodeId, issueId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }))
                .flatMap(issue -> {
                    String sourceStatusKey = issue.getStatusKey();

                    if (Objects.equals(sourceStatusKey, targetStatusKey)) {
                        log.info("[{}][{}] Old status[{}] and new status[{}] are the same for issue with ID {}",
                                requestId, nodeId, sourceStatusKey, targetStatusKey, issue.getId());

                        return loadIssueWithHistory(issue);
                    }
                    return issueRepository.changeStatus(issue.getId(), targetStatusKey, issue.getVersion())
                            .switchIfEmpty(Mono.defer(() -> {
                                log.warn("[{}][{}] Issue status was modified by another process: issueId={}",
                                        requestId, nodeId, issue.getId());

                                return Mono.error(new DomainException(DomainStatus.ABORTED, "Issue status was modified concurrently"));
                            }))
                            .flatMap(savedIssue -> {
                                JsonNode payload = payloadSerializer.createTransitionedPayload(sourceStatusKey, targetStatusKey, transitionId, actorUserId, issue.getAssigneeId());

                                return issueHistoryService.saveIssueHistory(requestId, nodeId, issue, actorUserId, IssueEventType.TRANSITIONED, payload)
                                        .then(outboxEventService.saveOutboxEvent(requestId, nodeId, issue.getId(), EventType.ISSUE_TRANSITIONED, payload))
                                        .thenReturn(savedIssue);
                            })
                            .flatMap(this::loadIssueWithHistory);
                })
                .doOnSuccess(result -> {
                    if (result != null) {
                        log.info("[{}][{}] Issue with id {} successfully transitioned: statusKey={}",
                                requestId, nodeId, result.getIssue().getId(), result.getIssue().getStatusKey());
                    }
                });
    }

    private Mono<IssueWithHistory> loadIssueWithHistory(Issue issue) {
        return issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issue.getId(), Limit.of(issueProperties.card().maxHistorySize()))
                .collectList()
                .map(history -> new IssueWithHistory(issue, history));
    }

}