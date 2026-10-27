package ru.taska.service;

import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeDeletion;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueTypeDeletionRepository;

/** Recoverable forward saga: after the issue receipt commits there is no compensating data loss. */
@Service
@RequiredArgsConstructor
@Slf4j
public class IssueTypeDeletionCoordinator {
  private final IssueTypeDeletionTransactions transactions;
  private final IssueTypeDeletionRepository journal;
  private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueStub;
  private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
  private static final Set<DomainStatus> REJECTED=Set.of(DomainStatus.INVALID_ARGUMENT,DomainStatus.FAILED_PRECONDITION,
      DomainStatus.PERMISSION_DENIED,DomainStatus.UNAUTHENTICATED,DomainStatus.NOT_FOUND,DomainStatus.ALREADY_EXISTS);

  public Mono<Void> delete(UUID project,UUID actor,String source,String target,Map<String,String> mapping,
      UUID expected,Header header) {
    return Mono.deferContextual(context->transactions.begin(project,actor,source,target,mapping,expected,
            header.getRequestId(),header.getNodeId(),ActionMetadata.current(context)))
        .flatMap(this::drive);
  }

  public Mono<Void> drive(IssueTypeDeletion item) {
    return switch(item.state()) {
      case "COMPLETED" -> release(item).onErrorMap(error->IssueTypeDeletionTransactions.pendingError(item.id()));
      case "CANCELLED" -> release(item).then(Mono.error(rejection(item)));
      case "CANCELLING" -> migration().cancel(client.IssueTypeDeletionClients.key(item.projectId(),item.id()))
          .onErrorMap(error->IssueTypeDeletionTransactions.pendingError(item.id())).flatMap(result->finish(item,result));
      case "PENDING" -> reserve(item).then(migration().apply(client.IssueTypeDeletionClients.key(item.projectId(),item.id())))
          .onErrorResume(error->{
            DomainException rejection=domain(error);
            if(REJECTED.contains(rejection.getStatus()))return transactions.requestCancellation(item,rejection)
                .flatMap(this::drive).then(Mono.<IssueTypeMoveOutcome>empty());
            return Mono.error(IssueTypeDeletionTransactions.pendingError(item.id()));
          }).flatMap(result->finish(item,result));
      default -> Mono.error(new IllegalStateException("Invalid deletion state"));
    };
  }

  private Mono<Void> finish(IssueTypeDeletion item,IssueTypeMoveOutcome result) {
    if(result.getState()==IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED)
      return transactions.complete(item).then(release(item)).onErrorMap(error->IssueTypeDeletionTransactions.pendingError(item.id()));
    if(result.getState()==IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED)
      return journal.find(item.projectId(),item.id()).flatMap(latest->transactions.cancel(latest).then(release(latest))
          .onErrorMap(error->IssueTypeDeletionTransactions.pendingError(item.id())).then(Mono.error(rejection(latest))));
    if(result.getState()==IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_REJECTED) {
      var error=new DomainException(DomainStatus.valueOf(result.getRejectionStatus()),result.getRejectionMessage(),result.getRejectionDetailsMap());
      if(REJECTED.contains(error.getStatus()))return transactions.requestCancellation(item,error).flatMap(this::drive);
    }
    return Mono.error(IssueTypeDeletionTransactions.pendingError(item.id()));
  }
  private Mono<Void> reserve(IssueTypeDeletion item) {
    return item.target()==null?Mono.empty():reservation().reserve(client.IssueTypeDeletionClients.key(item.projectId(),item.id())).then();
  }
  private Mono<Void> release(IssueTypeDeletion item) {
    if(item.reservationReleased())return Mono.empty();
    Mono<Void> release=item.target()==null?Mono.empty():reservation().release(client.IssueTypeDeletionClients.key(item.projectId(),item.id())).then();
    return release.then(journal.released(item));
  }
  private ReactorIssueTypeIssueMigrationGrpc.ReactorIssueTypeIssueMigrationStub migration() {
    return ReactorIssueTypeIssueMigrationGrpc.newReactorStub(issueStub.getChannel()).withDeadlineAfter(90,TimeUnit.SECONDS);
  }
  private ReactorIssueTypeWorkflowReservationGrpc.ReactorIssueTypeWorkflowReservationStub reservation() {
    return ReactorIssueTypeWorkflowReservationGrpc.newReactorStub(workflowStub.getChannel()).withDeadlineAfter(20,TimeUnit.SECONDS);
  }

  @Scheduled(fixedDelayString="${app.issue-type-deletions.recovery-interval:5s}")
  public Mono<Void> recover() {
    return Mono.defer(()->journal.recoverable().concatMap(item->drive(item).onErrorResume(error->{
      log.warn("Issue type deletion recovery pending: operation={}, status={}",item.id(),domain(error).getStatus());
      return journal.touched(item).onErrorResume(unavailable->Mono.empty());
    })).then());
  }
  private static DomainException rejection(IssueTypeDeletion item) {
    return new DomainException(item.errorStatus()==null?DomainStatus.ABORTED:DomainStatus.valueOf(item.errorStatus()),
        item.errorMessage()==null?"Issue type deletion was cancelled":item.errorMessage(),item.errorDetails());
  }
  private static DomainException domain(Throwable error) {
    if(error instanceof DomainException domain)return domain;
    if(error instanceof StatusRuntimeException grpc) {
      var code=grpc.getStatus().getCode();
      return new DomainException(code==io.grpc.Status.Code.OK?DomainStatus.INTERNAL:DomainStatus.valueOf(code.name()),
          grpc.getStatus().getDescription()==null?"Issue type deletion unavailable":grpc.getStatus().getDescription(),
          mapper.GrpcErrorDetails.decode(grpc.getTrailers()));
    }
    return new DomainException(DomainStatus.UNAVAILABLE,"Issue type deletion unavailable");
  }
}
