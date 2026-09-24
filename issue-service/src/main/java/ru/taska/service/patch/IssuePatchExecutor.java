package ru.taska.service.patch;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.PatchIssueResult;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.service.watcher.IssueAutoWatchService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

/**
 * Сервис-исполнитель для атомарных операций с БД в контексте PATCH-обновления задачи.
 * Проверки прав через gRPC выполняются до вызова, вне транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssuePatchExecutor {

    private final IssueRepository issueRepository;
    private final IssueWatcherRepository issueWatcherRepository;
    private final PayloadSerializer payloadSerializer;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final IssueAutoWatchService issueAutoWatchService;

    /**
     * Транзакционное применение PATCH-изменений к задаче.
     * Задача блокируется на время транзакции, версия повторно сверяется с {@code ifMatchVersion}:
     * если её успели изменить после проверок, изменения не применяются и возвращается конфликт версий.
     * Изменения применяются к заблокированной строке, а не к прочитанной до транзакции копии.
     */
    @Transactional
    public Mono<PatchIssueResult> executePatch(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            int ifMatchVersion,
            IssuePatch patch
    ) {
        return issueRepository.findActiveByIdForUpdate(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: {} was not found", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " was not found"));
                }))
                .flatMap(issue -> {
                    if (issue.getVersion() != ifMatchVersion) {
                        log.info("[{}][{}] Issue with id: {} was modified concurrently: current version={}, If-Match version={}",
                                requestId, nodeId, issueId, issue.getVersion(), ifMatchVersion);
                        return Mono.just(new PatchIssueResult(issue, true));
                    }

                    return applyPatch(requestId, nodeId, issue, patch.applyTo(issue), actorUserId)
                            .map(savedIssue -> new PatchIssueResult(savedIssue, false));
                });
    }

    /**
     * Сохраняет изменения PATCH-запроса. Если ничего не изменилось — возвращает задачу без сохранения
     * и без увеличения версии. Изменения полей пишутся событием UPDATED, смена исполнителя — отдельным
     * событием ASSIGNED, как при обычном назначении.
     */
    private Mono<Issue> applyPatch(String requestId, String nodeId, Issue issue, Issue patched, UUID actorUserId) {
        UUID previousAssigneeId = issue.getAssigneeId();
        boolean assigneeChanged = !Objects.equals(patched.getAssigneeId(), previousAssigneeId);

        return issueWatcherRepository.findUserIdsByIssueId(issue.getId())
                .collectList()
                .flatMap(watcherIds -> {
                    JsonNode updatedPayload = payloadSerializer.createIssueUpdatedPayload(
                            issue, actorUserId, patched.getAssigneeId(),
                            patched.getSummary(), patched.getDescription(), patched.getPriority(),
                            patched.getStoryPoints(), patched.getStartDate(), patched.getDueDate(),
                            patched.getOriginalEstimateMinutes(), patched.getRemainingEstimateMinutes(),
                            watcherIds
                    );

                    if (updatedPayload.isEmpty() && !assigneeChanged) {
                        log.info("[{}][{}] Issue with id: {} not changed by patch from user with id: {}",
                                requestId, nodeId, issue.getId(), actorUserId);
                        return Mono.just(issue);
                    }

                    patched.setUpdatedAt(Instant.now());
                    patched.setVersion(issue.getVersion() + 1);

                    return issueRepository.save(patched)
                            .flatMap(savedIssue -> saveUpdatedEvent(requestId, nodeId, savedIssue, actorUserId, updatedPayload)
                                    .then(Mono.defer(() -> handleAssigneeChange(
                                            requestId, nodeId, savedIssue, previousAssigneeId, actorUserId, watcherIds)))
                                    .then(Mono.fromRunnable(() ->
                                            log.debug("[{}][{}] Issue with id: {} successfully patched by user with id: {}",
                                                    requestId, nodeId, savedIssue.getId(), actorUserId)))
                                    .thenReturn(savedIssue));
                });
    }

    /**
     * Пишет событие UPDATED в outbox и историю. Если поля задачи не изменились ({@code payload} пустой) — ничего не делает.
     */
    private Mono<Void> saveUpdatedEvent(String requestId, String nodeId, Issue savedIssue, UUID actorUserId, JsonNode payload) {
        if (payload.isEmpty()) {
            return Mono.empty();
        }

        return outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE, savedIssue.getId(),
                        EventType.ISSUE_UPDATED, payload)
                .then(issueHistoryService.saveIssueHistory(requestId, nodeId, savedIssue.getId(),
                        actorUserId, IssueEventType.UPDATED, payload))
                .then();
    }

    /**
     * Обрабатывает смену исполнителя так же, как {@code assignIssue}: пишет событие ASSIGNED в историю и outbox
     * и добавляет нового исполнителя в наблюдатели. Если исполнитель не изменился — ничего не делает.
     */
    private Mono<Void> handleAssigneeChange(String requestId, String nodeId, Issue savedIssue, UUID previousAssigneeId,
                                            UUID actorUserId, List<UUID> watcherIds) {
        if (Objects.equals(savedIssue.getAssigneeId(), previousAssigneeId)) {
            return Mono.empty();
        }

        JsonNode payload = payloadSerializer.createIssueAssignedPayload(
                previousAssigneeId, savedIssue.getAssigneeId(), actorUserId, watcherIds);

        return issueHistoryService.saveIssueHistory(requestId, nodeId, savedIssue.getId(),
                        actorUserId, IssueEventType.ASSIGNED, payload)
                .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE,
                        savedIssue.getId(), EventType.ISSUE_ASSIGNED, payload))
                .then(issueAutoWatchService.watchAssigneeOnAssign(requestId, nodeId, savedIssue, actorUserId));
    }
}
