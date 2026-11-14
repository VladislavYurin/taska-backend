package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalResponse;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueByKeyRequest;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.GetProjectTaskQuotasRequest;
import ru.taska.api.issue.v1.GetProjectTaskQuotasResponse;
import ru.taska.api.issue.v1.IssueEventType;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

/** The existing IssueService contract, admitted only through message-bound native transport. */
@GrpcService
public final class TenantIssueGrpc extends ReactorIssueServiceGrpc.IssueServiceImplBase {
  private final TenantIssueApi api;

  public TenantIssueGrpc(TenantIssueApi api) {
    this.api = api;
  }

  @Override
  public Mono<CountProjectIssuesBatchInternalResponse> countProjectIssuesBatchInternal(
      Mono<CountProjectIssuesBatchInternalRequest> q) {
    return errors(q.flatMap(api::counts));
  }

  @Override
  public Mono<GetProjectTaskQuotasResponse> getProjectTaskQuotas(
      Mono<GetProjectTaskQuotasRequest> q) {
    return errors(q.flatMap(api::quotas));
  }

  @Override
  public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> q) {
    return errors(q.flatMap(r -> api.create(r.getBody())).map(TenantIssueApi::response));
  }

  @Override
  public Mono<IssueWithHistoryResponse> getIssue(Mono<GetIssueRequest> q) {
    return errors(q.flatMap(r -> api.getWithHistory(r.getBody())));
  }

  @Override
  public Mono<GetIssueDetailsResponse> getIssueDetails(Mono<GetIssueRequest> q) {
    return errors(q.flatMap(r -> api.details(r.getBody())));
  }

  @Override
  public Mono<IssueResponse> getIssueByKey(Mono<GetIssueByKeyRequest> q) {
    return errors(
        q.flatMap(r -> api.getByKey(r.getBody().getIssueKey(), r.getBody().getActorUserId()))
            .map(TenantIssueApi::response));
  }

  @Override
  public Mono<PatchIssueResponse> patchIssue(Mono<PatchIssueRequest> q) {
    return errors(
        q.flatMap(r -> api.patch(r.getBody(), r.getHeader().getRequestId()))
            .map(
                row ->
                    PatchIssueResponse.newBuilder()
                        .setIssue(TenantIssueApi.response(row))
                        .build()));
  }

  @Override
  public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> q) {
    return errors(
        q.flatMap(r -> api.update(r.getBody(), r.getHeader().getRequestId()))
            .map(
                row -> {
                  var issue = TenantIssueApi.response(row);
                  var b =
                      UpdateIssueResponse.newBuilder()
                          .setUpdatedIssueId(issue.getId())
                          .setSummary(issue.getSummary())
                          .setDescription(issue.getDescription())
                          .setPriority(issue.getPriority());
                  if (issue.hasStoryPoints()) b.setStoryPoints(issue.getStoryPoints());
                  if (issue.hasStartDate()) b.setStartDate(issue.getStartDate());
                  if (issue.hasDueDate()) b.setDueDate(issue.getDueDate());
                  if (issue.hasOriginalEstimateMinutes())
                    b.setOriginalEstimateMinutes(issue.getOriginalEstimateMinutes());
                  if (issue.hasRemainingEstimateMinutes())
                    b.setRemainingEstimateMinutes(issue.getRemainingEstimateMinutes());
                  return b.build();
                }));
  }

  @Override
  public Mono<IssueResponse> assignIssue(Mono<AssignIssueRequest> q) {
    return errors(
        q.flatMap(r -> api.assign(r.getBody(), r.getHeader().getRequestId()))
            .map(TenantIssueApi::response));
  }

  @Override
  public Mono<IssueWithHistoryResponse> transitionIssue(Mono<TransitionIssueRequest> q) {
    return errors(
        q.flatMap(r -> api.transition(r.getBody(), r.getHeader().getRequestId()))
            .map(
                row ->
                    IssueWithHistoryResponse.newBuilder()
                        .setIssue(TenantIssueApi.response(row))
                        .build()));
  }

  @Override
  public Mono<DeleteIssueResponse> deleteIssue(Mono<DeleteIssueRequest> q) {
    return errors(
        q.flatMap(r -> api.delete(r.getBody(), r.getHeader().getRequestId()))
            .map(
                row ->
                    DeleteIssueResponse.newBuilder()
                        .setDeletedIssueId(row.identity().id().toString())
                        .setIssueEventType(IssueEventType.ISSUE_EVENT_TYPE_DELETED)
                        .build()));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
