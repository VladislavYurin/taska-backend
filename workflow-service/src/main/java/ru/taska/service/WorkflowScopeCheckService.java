package ru.taska.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;

/** Constant-size validation for issue writers; unrelated graphs and their text never cross the RPC boundary. */
@Service
@RequiredArgsConstructor
public class WorkflowScopeCheckService {
    private final GrpcProjectServiceClient projects;
    private final WorkflowWriteFence fence;
    private final WorkflowProperties properties;
    private final DatabaseClient database;
    private record Scope(UUID id,int version) { }

    public Mono<Void> check(Header header,UUID project,UUID actor,List<String> requested,
            String expectedWorkflow,Integer expectedVersion,String status) {
        // This registry RPC enforces current project membership before pending state can be disclosed.
        return projects.listIssueTypeDetails(header.getRequestId(),header.getNodeId(),project,actor)
                .flatMap(registry -> {
                    if (requested.isEmpty() || requested.stream().anyMatch(code -> !code.matches("^[A-Z][A-Z0-9_]{1,31}$")))
                        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"A valid issue-type scope is required"));
                    if ((expectedWorkflow==null)!=(expectedVersion==null) || expectedVersion!=null&&expectedVersion<1)
                        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"expected workflow id and positive version must be supplied together"));
                    UUID expected=null;
                    if (expectedWorkflow!=null) {
                        try { expected=UUID.fromString(expectedWorkflow); }
                        catch (IllegalArgumentException invalid) { return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid expected workflow id")); }
                    }
                    if (status!=null&&!status.matches("^[A-Z][A-Z0-9_]{1,63}$"))
                        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid status key"));
                    var types=new LinkedHashMap<String,ProjectIssueTypeResponse>();
                    for (String code:requested) {
                        var found=registry.stream().filter(type -> type.getCode().equals(code)).findFirst();
                        if (found.isEmpty()) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                                "Issue type is not registered in this project",Map.of("reason","ISSUE_TYPE_NOT_IN_PROJECT")));
                        types.putIfAbsent(code,found.get());
                    }
                    UUID expectedId=expected;
                    Mono<Scope> fallback=Mono.defer(this::defaultScope).cache();
                    return fence.requireReadable(project).thenMany(Flux.fromIterable(types.values()))
                            .concatMap(type -> resolve(project,type).switchIfEmpty(fallback).flatMap(actual -> {
                                if (expectedId!=null&&(!expectedId.equals(actual.id())||expectedVersion!=actual.version()))
                                    return Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow binding or version changed",
                                            Map.of("reason","WORKFLOW_CHANGED")));
                                return status==null?Mono.empty():database.sql("SELECT EXISTS(SELECT 1 FROM taska.statuses WHERE workflow_id=:workflow AND status_key=:status)")
                                        .bind("workflow",actual.id()).bind("status",status).map(row -> Boolean.TRUE.equals(row.get(0,Boolean.class))).one()
                                        .flatMap(valid -> valid?Mono.empty():Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                                "Initial status is not available for the issue type",Map.of("reason","STATUS_NOT_IN_WORKFLOW"))));
                            })).then();
                });
    }
    private Mono<Scope> resolve(UUID project,ProjectIssueTypeResponse type) {
        UUID generation=IssueTypeBindingIdentity.generation(type);
        return database.sql("""
                SELECT w.id,w.version FROM taska.workflow_bindings b JOIN taska.workflows w ON w.id=b.workflow_id
                WHERE b.project_id=:project AND b.issue_type=:code AND w.is_active=true
                  AND (b.issue_type_generation=:generation OR (b.issue_type_generation IS NULL AND :legacy))
                LIMIT 1
                """).bind("project",project).bind("code",type.getCode()).bind("generation",generation)
                .bind("legacy",generation.equals(IssueTypeBindingIdentity.initialGeneration(project,type.getCode())))
                .map(row -> new Scope(row.get("id",UUID.class),row.get("version",Integer.class))).one();
    }
    private Mono<Scope> defaultScope() {
        return database.sql("""
                SELECT w.id,w.version FROM taska.workflow_bindings b JOIN taska.workflows w ON w.id=b.workflow_id
                WHERE b.project_id=:project AND b.issue_type='TASK' AND w.is_active=true LIMIT 1
                """).bind("project",properties.defaultProjectId())
                .map(row -> new Scope(row.get("id",UUID.class),row.get("version",Integer.class))).one()
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Default workflow is unavailable")));
    }
}
