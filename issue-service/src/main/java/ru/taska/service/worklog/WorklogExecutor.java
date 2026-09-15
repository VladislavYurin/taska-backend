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
                    issue.addWorklogMinutes(dto.spentMinutes());

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
                            .then(worklogRepository.save(worklog));
                })
                .flatMap(savedWorklog -> {
                    JsonNode payload = payloadSerializer.createWorklogAddedPayload(savedWorklog);

                    return saveAuditAndOutbox(
                            requestId, nodeId, issueId, actorUserId,
                            IssueEventType.WORKLOG_ADDED,
                            EventType.WORKLOG_ADDED,
                            payload,
                            savedWorklog,
                            "successfully added worklog with id: " + savedWorklog.getId()
                    );
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
                .flatMap(issue -> findActiveWorklogForUpdate(requestId, nodeId, worklogId)
                        .flatMap(worklog -> {
                            validateWorklogBelongsToIssue(requestId, nodeId, issueId, worklog);

                            if (worklogDto.spentMinutes() != null) {
                                issue.updateWorklogMinutes(worklog.getSpentMinutes(), worklogDto.spentMinutes());
                            } else {
                                issue.touch();
                            }
                            worklog.update(worklogDto.spentMinutes(), worklogDto.workDate(), worklogDto.comment());

                            return issueRepository.save(issue)
                                    .then(worklogRepository.save(worklog));
                        }))
                .flatMap(savedWorklog -> {
                    JsonNode payload = payloadSerializer.createWorklogUpdatePayload(savedWorklog);

                    return saveAuditAndOutbox(
                            requestId, nodeId, issueId, actorUserId,
                            IssueEventType.WORKLOG_UPDATED,
                            EventType.WORKLOG_UPDATED,
                            payload,
                            savedWorklog,
                            "successfully update worklog with id: " + savedWorklog.getId()
                    );
                });
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
                    validateWorklogBelongsToIssue(requestId, nodeId, issueId, worklog);
                    return findActiveIssueForUpdate(requestId, nodeId, worklog.getIssueId());
                })
                .flatMap(issue -> worklogRepository.softDelete(worklogId)
                        .switchIfEmpty(Mono.defer(() -> Mono.error(new DomainException(
                                DomainStatus.NOT_FOUND,
                                "Worklog with id: " + worklogId + " not found"
                        ))))
                        .flatMap(deletedWorklog -> {
                            issue.removeWorklogMinutes(deletedWorklog.getSpentMinutes());

                            return issueRepository.save(issue)
                                    .thenReturn(deletedWorklog);
                        })
                )
                .flatMap(deletedWorklog -> {
                    JsonNode payload = payloadSerializer.createWorklogDeletedPayload(
                            issueId, worklogId, actorUserId, deletedWorklog.getDeletedAt());

                    return saveAuditAndOutbox(
                            requestId, nodeId, issueId, actorUserId,
                            IssueEventType.WORKLOG_DELETED, EventType.WORKLOG_DELETED,
                            payload, deletedWorklog,
                            "successfully delete worklog with id: " + deletedWorklog.getId()
                    );
                });

    }

    private void validateWorklogBelongsToIssue(String requestId, String nodeId, UUID issueId, Worklog worklog) {
        if (!worklog.getIssueId().equals(issueId)) {
            log.warn("[{}][{}] Issue {} doesnt belong to worklog {}", requestId, nodeId, issueId, worklog.getId());
            throw new DomainException(DomainStatus.NOT_FOUND, "Issue doesnt belong to worklog");
        }
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

    private Mono<Worklog> findActiveWorklogForUpdate(
            String requestId,
            String nodeId,
            UUID worklogId
    ) {
        return worklogRepository.findActiveByIdForUpdate(worklogId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Worklog with id: {} was not found", requestId, nodeId, worklogId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Worklog with id: " + worklogId + " not found"
                    ));
                }));
    }

    private Mono<Worklog> saveAuditAndOutbox(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            IssueEventType issueEventType,
            EventType eventType,
            JsonNode payload,
            Worklog resultWorklog,
            String logMessage
    ) {
        return issueHistoryService.saveIssueHistory(
                        requestId, nodeId, issueId, actorUserId, issueEventType, payload)
                .then(outboxEventService.saveOutboxEvent(
                        requestId, nodeId, AggregateType.ISSUE, actorUserId, eventType, payload))
                .then(Mono.fromRunnable(() ->
                        log.debug("[{}][{}] User with id: {} {}", requestId, nodeId, actorUserId, logMessage)))
                .thenReturn(resultWorklog);
    }
}
