package ru.taska.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.repository.WorkflowBindingReceiptRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class WorkflowBindingMigrationService {
    private final IssueTypeWriteGuard lock;
    private final WorkflowBindingReceiptRepository receipts;
    private final IssueRepository issues;
    private final IssueWatcherRepository watchers;
    private final GrpcProjectServiceClient projects;
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    private final IssueHistoryService history;
    private final OutboxEventService outbox;
    private final PayloadSerializer serializer;
    private final ObjectMapper mapper;

    @Transactional
    public Mono<TypeOperationOutcome> apply(UUID project,UUID operation) {
        return lock.lock(project).then(receipts.find(project,operation)).switchIfEmpty(Mono.defer(()->intent(project,operation)
                .flatMap(intent->{
                    if(!intent.getState().equals("PENDING"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow operation is not pending"));
                    UUID actor=UUID.fromString(intent.getActorUserId());
                    return authorize(intent,project,actor).then(registry(intent,project,actor)).then(move(intent,project,actor))
                            .contextWrite(context->action.GrpcActionContext.install(context,intent.getOriginalHeader()));
                })));
    }
    @Transactional
    public Mono<TypeOperationOutcome> cancel(UUID project,UUID operation) {
        return lock.lock(project).then(receipts.find(project,operation)).switchIfEmpty(Mono.defer(()->intent(project,operation).flatMap(intent->{
            if(!List.of("CANCELLING","CANCELLED").contains(intent.getState()))return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Cancellation must be durable"));
            return receipts.insert(intent,"CANCELLED",0);
        })));
    }
    @Transactional
    public Mono<TypeOperationOutcome> release(UUID project,UUID operation) {
        return lock.lock(project).then(intent(project,operation)).flatMap(intent->{
            if(!List.of("COMPLETED","CANCELLED").contains(intent.getState()))return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Workflow operation is not terminal"));
            return receipts.find(project,operation).switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow migration receipt is missing")))
                    .flatMap(saved->receipts.release(project,operation).thenReturn(saved));
        });
    }
    private Mono<WorkflowBindingIntent> intent(UUID project,UUID operation) {
        return ReactorWorkflowBindingJournalGrpc.newReactorStub(workflowStub.getChannel()).withDeadlineAfter(5,TimeUnit.SECONDS)
                .get(client.TypeOperationClients.key(project,operation)).flatMap(intent->{
                    if(!intent.getProjectId().equals(project.toString())||!intent.getOperationId().equals(operation.toString()))
                        return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow journal identity mismatch"));
                    return Mono.just(intent);
                });
    }
    private Mono<Void> authorize(WorkflowBindingIntent intent,UUID project,UUID actor) {
        return projects.checkProjectRole(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),project,actor)
                .flatMap(role->{
                    if(!role.getProjectExists())return Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found"));
                    if(!role.getIsMember()||role.getRole()!=ProjectRole.PROJECT_ROLE_ADMIN)return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN role is required"));
                    if(!role.hasProjectArchived())return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Archive-aware project service is required"));
                    return role.getProjectArchived()?Mono.error(ru.taska.exception.ProjectArchived.error()):Mono.empty();
                });
    }
    private Mono<Void> registry(WorkflowBindingIntent intent,UUID project,UUID actor) {
        return projects.listIssueTypeDetails(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),project,actor).flatMap(types->{
            boolean valid=intent.getMovesList().stream().allMatch(move->types.stream().anyMatch(type->type.getCode().equals(move.getCode())
                    &&type.getGeneration().equals(move.getGeneration())&&type.getPendingOperationId().equals(intent.getOperationId())));
            return valid?Mono.empty():Mono.error(new DomainException(DomainStatus.ABORTED,"Issue type lifecycle changed",Map.of("reason","ISSUE_TYPES_CHANGED")));
        });
    }
    private Mono<TypeOperationOutcome> move(WorkflowBindingIntent intent,UUID project,UUID actor) {
        var moved=intent.getMovesList().stream().filter(m->!m.getSourceWorkflowId().equals(m.getTargetWorkflowId())).toList();
        if(moved.stream().map(WorkflowTypeMove::getCode).distinct().count()!=moved.size())return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid workflow migration destinations"));
        if(moved.isEmpty())return receipts.insert(intent,"APPLIED",0);
        var byCode=moved.stream().collect(Collectors.toMap(WorkflowTypeMove::getCode,Function.identity()));
        var destinations=moved.stream().map(m->Map.of("code",m.getCode(),"statuses",m.getTargetStatusKeysList())).toList();
        return issues.findForWorkflowBindingMove(project,mapper.writeValueAsString(destinations),mapper.writeValueAsString(intent.getStatusMappingsMap()))
                .collectList().flatMap(rows->{
                    if(rows.size()>5000)return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Synchronous workflow migration is limited to 5000 issues",
                            Map.of("reason","WORKFLOW_MIGRATION_LIMIT","limit","5000")));
                    var unresolved=rows.stream().filter(row->!byCode.get(row.getIssueType().name()).getTargetStatusKeysList()
                            .contains(intent.getStatusMappingsMap().getOrDefault(row.getStatusKey(),row.getStatusKey()))).toList();
                    if(!unresolved.isEmpty()) {
                        boolean badMapping=unresolved.stream().anyMatch(row->intent.getStatusMappingsMap().containsKey(row.getStatusKey()));
                        return Mono.error(new DomainException(badMapping?DomainStatus.INVALID_ARGUMENT:DomainStatus.FAILED_PRECONDITION,
                                "Supply a valid target status mapping for the destination workflow",details(unresolved,byCode,badMapping)));
                    }
                    return Flux.fromIterable(rows).concatMap(row->{
                        String target=intent.getStatusMappingsMap().getOrDefault(row.getStatusKey(),row.getStatusKey());
                        if(target.equals(row.getStatusKey()))return Mono.just(0L);
                        return issues.changeStatus(row.getId(),target,row.getVersion())
                                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Issue changed during workflow migration")))
                                .flatMap(saved->watchers.findUserIdsByIssueId(saved.getId()).collectList().flatMap(ids->{
                                    ObjectNode payload=(ObjectNode)serializer.createTransitionedPayload(saved,row.getStatusKey(),target,null,actor,saved.getAssigneeId(),ids);
                                    payload.put("reason","WORKFLOW_CONFIGURATION_CHANGED").put("workflowId",byCode.get(row.getIssueType().name()).getTargetWorkflowId());
                                    return history.saveIssueHistory(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),saved.getId(),actor,IssueEventType.TRANSITIONED,payload)
                                            .then(outbox.saveOutboxEvent(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),AggregateType.ISSUE,saved.getId(),EventType.ISSUE_TRANSITIONED,payload))
                                            .thenReturn(1L);
                                }));
                    }).reduce(0L,Long::sum).flatMap(count->receipts.insert(intent,"APPLIED",count));
                });
    }
    private Map<String,String> details(List<Issue> rows,Map<String,WorkflowTypeMove> moves,boolean badMapping) {
        var statuses=new LinkedHashMap<String,Set<String>>(); var targets=new LinkedHashMap<String,List<String>>();
        for(var row:rows) {statuses.computeIfAbsent(row.getIssueType().name(),code->new java.util.TreeSet<>()).add(row.getStatusKey());
            targets.putIfAbsent(row.getIssueType().name(),moves.get(row.getIssueType().name()).getTargetStatusKeysList());}
        Set<String> intersection=null;
        for(var keys:targets.values()) {if(intersection==null)intersection=new java.util.TreeSet<>(keys);else intersection.retainAll(keys);}
        var details=new LinkedHashMap<String,String>();
        details.put("reason",badMapping?"WORKFLOW_STATUS_MAPPING_INVALID":"WORKFLOW_ISSUES_UNMAPPED");
        details.put("issueCount",Integer.toString(rows.size()));
        details.put("statusKeys",rows.stream().map(Issue::getStatusKey).distinct().sorted().collect(Collectors.joining(",")));
        details.put("targetStatusKeys",intersection==null?"":String.join(",",intersection));
        details.put("targetStatusKeysByIssueType",mapper.writeValueAsString(targets));
        details.put("statusKeysByIssueType",mapper.writeValueAsString(statuses));
        return details;
    }
}
