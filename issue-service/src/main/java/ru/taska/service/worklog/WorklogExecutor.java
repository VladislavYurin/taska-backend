package ru.taska.service.worklog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Сервис-исполнитель для транзакционных операций с ворклогами задачи.
 * <p>
 * В первую очередь методы блокируют сущность issue, потом worklog (дли избежания deadlock)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorklogExecutor {

    private final IssueRepository issueRepository;
    private final IssueHistoryService issueHistoryService;
    private final WorklogRepository worklogRepository;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    @Transactional
    public Mono<Worklog> executeAdd(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            CreateWorklogDto dto
    ) {
        return findActiveIssueForUpdate(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    int currentSpent = issue.getTimeSpentMinutes() != null ? issue.getTimeSpentMinutes() : 0;
                    issue.setTimeSpentMinutes(currentSpent + dto.spentMinutes());

                    if (issue.getRemainingEstimateMinutes() != null) {
                        int newRemaining = issue.getRemainingEstimateMinutes() - dto.spentMinutes();
                        issue.setRemainingEstimateMinutes(Math.max(0, newRemaining));
                    }

                    issue.setUpdatedAt(Instant.now());
                    issue.setVersion(issue.getVersion() + 1);

                    Worklog worklog = Worklog.builder()
                            .issueId(issueId)
                            .projectId(issue.getProjectId())
                            .authorUserId(actorUserId)
                            .spentMinutes(dto.spentMinutes())
                            .comment(dto.comment())
                            .workDate(dto.workDate())
                            .version(1)
                            .build();

                    return issueRepository.save(issue)
                            .flatMap(savedIssue -> worklogRepository.save(worklog))
                            .flatMap(savedWorklog -> {
                                JsonNode payload = payloadSerializer.createWorklogAddedPayload(savedWorklog);

                                return issueHistoryService.saveIssueHistory(
                                                requestId,
                                                nodeId,
                                                issueId,
                                                actorUserId,
                                                IssueEventType.WORKLOG_ADDED,
                                                payload
                                        ).then(outboxEventService.saveOutboxEvent(
                                                requestId,
                                                nodeId,
                                                AggregateType.ISSUE,
                                                actorUserId,
                                                EventType.WORKLOG_ADDED,
                                                payload))
                                        .then(Mono.fromRunnable(() ->
                                                log.debug("[{}][{}] User with id: {} successfully added worklog to issue with id: {}",
                                                        requestId, nodeId, actorUserId, issueId)))
                                        .thenReturn(savedWorklog);
                            });
                });
    }


    @Transactional
    public Mono<Worklog> executeUpdate(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId,
            UpdateWorklogDto worklogDto
    ) {
        return findActiveIssueForUpdate(requestId, nodeId, issueId)
                .flatMap(issue -> worklogRepository.findActiveByIdForUpdate(worklogId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] Worklog with id: {} was not found", requestId, nodeId, worklogId);

                            return Mono.error(new DomainException(
                                    DomainStatus.NOT_FOUND,
                                    "Worklog with id: " + worklogId + " not found"
                            ));
                        }))
                        .flatMap(worklog -> {
                            if (!worklog.getIssueId().equals(issueId)) {
                                log.warn("[{}][{}] Issue {} doesnt belong to worklog {}", requestId, nodeId, issueId, worklogId);

                                return Mono.error(new DomainException(
                                        DomainStatus.NOT_FOUND,
                                        "Issue doesnt belong to worklog"
                                ));
                            }

                            if (worklogDto.spentMinutes() != null) {
                                int currentSpent = issue.getTimeSpentMinutes() != null ? issue.getTimeSpentMinutes() : 0;
                                int deltaSpent = worklog.getSpentMinutes() - worklogDto.spentMinutes();
                                issue.setTimeSpentMinutes(Math.max(0, currentSpent - deltaSpent));
                                worklog.setSpentMinutes(worklogDto.spentMinutes());

                                if (issue.getRemainingEstimateMinutes() != null) {
                                    int newRemaining = issue.getRemainingEstimateMinutes() + deltaSpent;
                                    issue.setRemainingEstimateMinutes(Math.max(0, newRemaining));
                                }
                            }

                            if (worklogDto.workDate() != null) {
                                worklog.setWorkDate(worklogDto.workDate());
                            }
                            if (worklogDto.comment() != null) {
                                worklog.setComment(worklogDto.comment());
                            }

                            issue.setUpdatedAt(Instant.now());
                            issue.setVersion(issue.getVersion() + 1);
                            worklog.setUpdatedAt(Instant.now());
                            worklog.setVersion(worklog.getVersion() + 1);

                            return issueRepository.save(issue)
                                    .flatMap(savedIssue -> worklogRepository.save(worklog))
                                    .flatMap(savedWorklog -> {
                                        JsonNode payload = payloadSerializer.createWorklogUpdatePayload(savedWorklog);

                                        return issueHistoryService.saveIssueHistory(
                                                        requestId,
                                                        nodeId,
                                                        issueId,
                                                        actorUserId,
                                                        IssueEventType.WORKLOG_UPDATED,
                                                        payload
                                                ).then(outboxEventService.saveOutboxEvent(
                                                        requestId,
                                                        nodeId,
                                                        AggregateType.ISSUE,
                                                        actorUserId,
                                                        EventType.WORKLOG_UPDATED,
                                                        payload))
                                                .then(Mono.fromRunnable(() ->
                                                        log.debug("[{}][{}] User with id: {} successfully update worklog to issue with id: {}",
                                                                requestId, nodeId, actorUserId, issueId)))
                                                .thenReturn(savedWorklog);
                                    });
                        }));
    }

    @Transactional
    public Mono<Worklog> executeDelete(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId
    ) {
        return findActiveWorklog(requestId, nodeId, worklogId)
                .flatMap(worklog -> {
                            if (!worklog.getIssueId().equals(issueId)) {
                                log.warn("[{}][{}] Issue {} doesnt belong to worklog {}", requestId, nodeId, issueId, worklogId);

                                return Mono.error(new DomainException(
                                        DomainStatus.NOT_FOUND,
                                        "Issue doesnt belong to worklog"
                                ));
                            }

                            return findActiveIssueForUpdate(requestId, nodeId, worklog.getIssueId())
                                    .flatMap(issue -> worklogRepository.softDelete(worklogId)
                                            .switchIfEmpty(Mono.defer(() -> Mono.error(new DomainException(
                                                    DomainStatus.NOT_FOUND,
                                                    "Worklog with id: " + worklogId + " not found"
                                            ))))
                                            .flatMap(deletedWorklog -> {


                                                if (issue.getRemainingEstimateMinutes() != null) {
                                                    issue.setRemainingEstimateMinutes(
                                                            issue.getRemainingEstimateMinutes() + deletedWorklog.getSpentMinutes()
                                                    );
                                                }
                                                if (issue.getTimeSpentMinutes() != null) {
                                                    issue.setTimeSpentMinutes(
                                                            Math.max(0, issue.getTimeSpentMinutes() - deletedWorklog.getSpentMinutes())
                                                    );
                                                }
                                                issue.setVersion(issue.getVersion() + 1);
                                                issue.setUpdatedAt(Instant.now());

                                                return issueRepository.save(issue)
                                                        .flatMap(savedIssue -> {
                                                            JsonNode payload = payloadSerializer.createWorklogDeletedPayload(
                                                                    issueId, worklogId, actorUserId, deletedWorklog.getDeletedAt());

                                                            return issueHistoryService.saveIssueHistory(
                                                                            requestId,
                                                                            nodeId,
                                                                            deletedWorklog.getIssueId(),
                                                                            actorUserId,
                                                                            IssueEventType.WORKLOG_DELETED,
                                                                            payload
                                                                    ).then(outboxEventService.saveOutboxEvent(
                                                                            requestId,
                                                                            nodeId,
                                                                            AggregateType.ISSUE,
                                                                            actorUserId,
                                                                            EventType.WORKLOG_DELETED,
                                                                            payload))
                                                                    .then(Mono.fromRunnable(() ->
                                                                            log.debug("[{}][{}] User with id: {} successfully delete worklog with id: {}",
                                                                                    requestId, nodeId, actorUserId, deletedWorklog.getId())))
                                                                    .thenReturn(deletedWorklog);
                                                        });
                                            })
                                    );
                        }
                );
    }


    private Mono<Worklog> findActiveWorklog(
            String requestId,
            String nodeId,
            UUID worklogId
    ) {
        return worklogRepository.findActiveById(worklogId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Worklog with id: {} was not found", requestId, nodeId, worklogId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Worklog with id: " + worklogId + " not found"
                    ));
                }));
    }

    private Mono<Issue> findActiveIssueForUpdate(
            String requestId,
            String nodeId,
            UUID issueId
    ) {
        return issueRepository.findActiveByIdForUpdate(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: {} was not found", requestId, nodeId, issueId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " not found"
                    ));
                }));
    }
}
