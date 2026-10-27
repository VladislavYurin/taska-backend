package ru.taska.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueTypeMoveReceiptRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.node.ObjectNode;

/** One issue transaction moves ALL live source rows and records its permanent idempotency receipt. */
@Service
@RequiredArgsConstructor
public class IssueTypeMoveService {
  private final IssueTypeWriteGuard guard;
  private final IssueTypeMoveReceiptRepository receipts;
  private final IssueRepository issues;
  private final IssueWatcherRepository watchers;
  private final GrpcProjectServiceClient projects;
  private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectStub;
  private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
  private final IssueHistoryService history;
  private final OutboxEventService outbox;
  private final PayloadSerializer serializer;

  @Transactional
  public Mono<IssueTypeMoveOutcome> apply(UUID project,UUID operation) {
    return guard.lock(project).then(receipts.find(project,operation)).switchIfEmpty(Mono.defer(()->intent(project,operation)
        .flatMap(intent->{
          if(!intent.getState().equals("PENDING"))return Mono.error(new DomainException(DomainStatus.ABORTED,"Deletion is not pending"));
          UUID actor=UUID.fromString(intent.getActorUserId());
          return authorize(intent,project,actor).then(validateRegistry(intent,project,actor))
              .then(intent.hasTargetCode()?move(intent,project,actor):deleteEmpty(intent,project))
              .contextWrite(context->action.GrpcActionContext.install(context,intent.getOriginalHeader()));
        })));
  }
  @Transactional
  public Mono<IssueTypeMoveOutcome> cancel(UUID project,UUID operation) {
    return guard.lock(project).then(receipts.find(project,operation)).switchIfEmpty(Mono.defer(()->intent(project,operation)
        .flatMap(intent->!intent.getState().equals("CANCELLING")&&!intent.getState().equals("CANCELLED")
            ?Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Cancellation requires a durable cancellation intent"))
            :receipts.insert(intent,"CANCELLED",0))));
  }
  private Mono<IssueTypeDeletionIntent> intent(UUID project,UUID operation) {
    return ReactorIssueTypeDeletionJournalGrpc.newReactorStub(projectStub.getChannel()).withDeadlineAfter(5,TimeUnit.SECONDS)
        .getIntent(client.IssueTypeDeletionClients.key(project,operation)).flatMap(intent->{
          if(!intent.getProjectId().equals(project.toString())||!intent.getOperationId().equals(operation.toString()))
            return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Deletion journal identity mismatch"));
          return Mono.just(intent);
        });
  }
  private Mono<Void> authorize(IssueTypeDeletionIntent intent,UUID project,UUID actor) {
    return projects.checkProjectRole(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),project,actor)
        .flatMap(role->{
          if(!role.getProjectExists())return Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found"));
          if(!role.getIsMember()||role.getRole()!=ProjectRole.PROJECT_ROLE_ADMIN)
            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN role is required"));
          if(!role.hasProjectArchived())return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Archive-aware project writes are required"));
          return role.getProjectArchived()?Mono.error(ru.taska.exception.ProjectArchived.error()):Mono.empty();
        });
  }
  private Mono<Void> validateRegistry(IssueTypeDeletionIntent intent,UUID project,UUID actor) {
    return projects.listIssueTypeDetails(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),project,actor)
        .flatMap(types->{
          var source=types.stream().filter(t->t.getCode().equals(intent.getSourceCode())&&t.getGeneration().equals(intent.getSourceGeneration())
              &&t.getPendingOperationId().equals(intent.getOperationId())).findFirst();
          boolean target=!intent.hasTargetCode()||types.stream().anyMatch(t->t.getCode().equals(intent.getTargetCode())
              &&t.getGeneration().equals(intent.getTargetGeneration())&&t.getPendingOperationId().equals(intent.getOperationId()));
          return source.isPresent()&&target?Mono.empty():Mono.error(new DomainException(DomainStatus.ABORTED,
              "Registry lifecycle does not match deletion intent",Map.of("reason","ISSUE_TYPE_CHANGED")));
        });
  }
  private Mono<IssueTypeMoveOutcome> deleteEmpty(IssueTypeDeletionIntent intent,UUID project) {
    return issues.countLiveByProjectAndType(project,intent.getSourceCode()).flatMap(count->count>0
        ?Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Issue type has live issues",
            Map.of("reason","ISSUE_TYPE_IN_USE","issueCount",count.toString())))
        :receipts.insert(intent,"APPLIED",0));
  }
  private Mono<IssueTypeMoveOutcome> move(IssueTypeDeletionIntent intent,UUID project,UUID actor) {
    return ReactorIssueTypeWorkflowReservationGrpc.newReactorStub(workflowStub.getChannel()).withDeadlineAfter(5,TimeUnit.SECONDS)
        .get(client.IssueTypeDeletionClients.key(project,UUID.fromString(intent.getOperationId())))
        .flatMap(snapshot->{
          if(!snapshot.getProjectId().equals(intent.getProjectId())||!snapshot.getOperationId().equals(intent.getOperationId())
              ||snapshot.getValidStatusKeysList().isEmpty())return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Workflow reservation mismatch"));
          var valid=snapshot.getValidStatusKeysList();
          if(!valid.containsAll(intent.getStatusMappingsMap().values()))return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
              "Mapped target status is outside the target workflow",Map.of("reason","ISSUE_TYPE_STATUS_MAPPING_INVALID","targetStatusKeys",String.join(",",valid))));
          return issues.findForIssueTypeMove(project,intent.getSourceCode()).collectList().flatMap(rows->{
            if(rows.size()>5000)return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                "Synchronous issue type migration is limited to 5000 issues",Map.of("reason","WORKFLOW_MIGRATION_LIMIT","limit","5000")));
            String unmapped=rows.stream().map(Issue::getStatusKey).distinct().filter(source->!valid.contains(
                intent.getStatusMappingsMap().getOrDefault(source,source))).sorted().collect(Collectors.joining(","));
            if(!unmapped.isEmpty())return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                "Supply mappings for source statuses absent from the target workflow",Map.of("reason","ISSUE_STATUSES_UNMAPPED",
                    "statusKeys",unmapped,"targetStatusKeys",String.join(",",valid))));
            return Flux.fromIterable(rows).concatMap(issue->moveIssue(intent,issue,actor)).then(receipts.insert(intent,"APPLIED",rows.size()));
          });
        });
  }
  private Mono<Void> moveIssue(IssueTypeDeletionIntent intent,Issue old,UUID actor) {
    String targetStatus=intent.getStatusMappingsMap().getOrDefault(old.getStatusKey(),old.getStatusKey());
    return issues.changeIssueTypeAndStatus(old.getId(),old.getVersion(),intent.getSourceCode(),intent.getTargetCode(),targetStatus)
        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Issue changed during type migration")))
        .flatMap(saved->watchers.findUserIdsByIssueId(saved.getId()).collectList().flatMap(ids->{
          ObjectNode payload=(ObjectNode)serializer.createIssuePatchedPayload(old,saved,actor,ids);
          if(saved.getAssigneeId()!=null)payload.put("assigneeId",saved.getAssigneeId().toString());
          payload.put("oldStatusKey",old.getStatusKey()).put("newStatusKey",saved.getStatusKey())
              .put("reason","ISSUE_TYPE_DELETED");
          return history.saveIssueHistory(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),
                  saved.getId(),actor,IssueEventType.UPDATED,payload)
              .then(outbox.saveOutboxEvent(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),
                  AggregateType.ISSUE,saved.getId(),EventType.ISSUE_UPDATED,payload)).then();
        }));
  }
}
