package ru.taska.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class WorkflowIssueMigrationService {
    private final IssueRepository repository;
    private final GrpcProjectServiceClient projects;
    private final IssueWatcherRepository watchers;
    private final PayloadSerializer serializer;
    private final IssueHistoryService history;
    private final OutboxEventService outbox;

    @Transactional
    public Mono<Long> migrate(UUID project, UUID actor, UUID workflow, List<String> types,
                               List<String> validStatuses, Map<String, String> mapping, String requestId, String nodeId) {
        return Mono.defer(() -> {
            if (types.isEmpty() || validStatuses.isEmpty()
                    || types.stream().anyMatch(t -> !t.matches("^[A-Z][A-Z0-9_]{1,31}$"))
                    || validStatuses.stream().anyMatch(s -> !s.matches("^[A-Z][A-Z0-9_]{1,63}$"))
                    || mapping.values().stream().anyMatch(v -> !validStatuses.contains(v))) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid workflow migration scope or target status"));
            }
            return projects.checkProjectRole(requestId, nodeId, project, actor)
                    .flatMap(role -> {
                        if (!role.getProjectExists()) return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                        if (!role.getIsMember() || role.getRole() != ProjectRole.PROJECT_ROLE_ADMIN) {
                            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Project ADMIN role is required"));
                        }
                        if (!role.hasProjectArchived()) {
                            return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,
                                    "Project service does not support archive-aware writes"));
                        }
                        if (role.getProjectArchived()) return Mono.error(ru.taska.exception.ProjectArchived.error());
                        return repository.findForWorkflowMigration(project, types, validStatuses,
                                mapping.isEmpty() ? List.of("") : mapping.keySet()).collectList();
                    })
                    .flatMap(issues -> {
                        if (issues.size() > 5000) return Mono.error(new DomainException(DomainStatus.RESOURCE_EXHAUSTED,
                                "Synchronous workflow migration is limited to 5000 issues"));
                        var unmapped = issues.stream().filter(issue -> !validStatuses.contains(
                                mapping.getOrDefault(issue.getStatusKey(), issue.getStatusKey()))).toList();
                        if (!unmapped.isEmpty()) {
                            String keys = unmapped.stream().map(issue -> issue.getStatusKey()).distinct().sorted().collect(Collectors.joining(","));
                            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                    "Workflow statuses have " + unmapped.size() + " issues; supply a target status mapping",
                                    Map.of("issueCount", Integer.toString(unmapped.size()), "statusKeys", keys)));
                        }
                        return Flux.fromIterable(issues).concatMap(issue -> {
                            String target = mapping.getOrDefault(issue.getStatusKey(), issue.getStatusKey());
                            if (target.equals(issue.getStatusKey())) return Mono.just(0L);
                            return repository.changeStatus(issue.getId(), target, issue.getVersion())
                                    .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED, "Issue changed concurrently")))
                                    .flatMap(saved -> watchers.findUserIdsByIssueId(saved.getId()).collectList().flatMap(ids -> {
                                        var payload = (ObjectNode) serializer.createTransitionedPayload(saved, issue.getStatusKey(),
                                                target, null, actor, saved.getAssigneeId(), ids);
                                        payload.put("reason", "WORKFLOW_CONFIGURATION_CHANGED");
                                        payload.put("workflowId", workflow.toString());
                                        return history.saveIssueHistory(requestId, nodeId, saved.getId(), actor, IssueEventType.TRANSITIONED, payload)
                                                .then(outbox.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE,
                                                        saved.getId(), EventType.ISSUE_TRANSITIONED, payload)).thenReturn(1L);
                                    }));
                        }).reduce(0L, Long::sum);
                    });
        });
    }
}
