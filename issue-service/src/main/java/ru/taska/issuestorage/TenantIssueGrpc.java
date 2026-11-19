package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AddIssueLabelRequest;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalResponse;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.DeleteProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelResponse;
import ru.taska.api.issue.v1.GetIssueByKeyRequest;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.GetIssueWatchStateRequest;
import ru.taska.api.issue.v1.GetIssueWatchStateResponse;
import ru.taska.api.issue.v1.GetProjectTaskQuotasRequest;
import ru.taska.api.issue.v1.GetProjectTaskQuotasResponse;
import ru.taska.api.issue.v1.IssueEventType;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueLabelsRequest;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListIssueWatchersRequest;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.RemoveIssueLabelRequest;
import ru.taska.api.issue.v1.RemoveIssueLabelResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UnwatchIssueRequest;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.api.issue.v1.UpdateProjectLabelRequest;
import ru.taska.api.issue.v1.WatchIssueRequest;
import ru.taska.api.issue.v1.WatchIssueResponse;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

/**
 * Существующий контракт IssueService, доступный только через нативный транспорт с привязкой к
 * сообщению.
 */
@GrpcService
public final class TenantIssueGrpc extends ReactorIssueServiceGrpc.IssueServiceImplBase {
  private final TenantIssueApi api;
  private final TenantIssueMetadataApi metadata;

  public TenantIssueGrpc(TenantIssueApi api, TenantIssueMetadataApi metadata) {
    this.api = api;
    this.metadata = metadata;
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
    return errors(
        q.flatMap(
            r ->
                api.getWithHistory(r.getBody())
                    .flatMap(value -> metadata.enrich(value, r.getBody().getActorUserId()))));
  }

  @Override
  public Mono<GetIssueDetailsResponse> getIssueDetails(Mono<GetIssueRequest> q) {
    return errors(
        q.flatMap(
            r ->
                api.details(r.getBody())
                    .flatMap(value -> metadata.enrich(value, r.getBody().getActorUserId()))));
  }

  @Override
  public Mono<IssueResponse> getIssueByKey(Mono<GetIssueByKeyRequest> q) {
    return errors(
        q.flatMap(
            r ->
                api.getByKey(r.getBody().getIssueKey(), r.getBody().getActorUserId())
                    .map(TenantIssueApi::response)
                    .flatMap(value -> metadata.enrich(value, r.getBody().getActorUserId()))));
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

  @Override
  public Mono<ProjectLabelResponse> createProjectLabel(Mono<CreateProjectLabelRequest> q) {
    return errors(q.flatMap(r -> metadata.create(r.getBody())));
  }

  @Override
  public Mono<ProjectLabelResponse> updateProjectLabel(Mono<UpdateProjectLabelRequest> q) {
    return errors(q.flatMap(r -> metadata.update(r.getBody())));
  }

  @Override
  public Mono<DeleteProjectLabelResponse> deleteProjectLabel(Mono<DeleteProjectLabelRequest> q) {
    return errors(q.flatMap(r -> metadata.delete(r.getBody())));
  }

  @Override
  public Mono<ListProjectLabelsResponse> listProjectLabels(Mono<ListProjectLabelsRequest> q) {
    return errors(q.flatMap(r -> metadata.projectLabels(r.getBody())));
  }

  @Override
  public Mono<AddIssueLabelResponse> addIssueLabel(Mono<AddIssueLabelRequest> q) {
    return errors(q.flatMap(r -> metadata.attach(r.getBody())));
  }

  @Override
  public Mono<RemoveIssueLabelResponse> removeIssueLabel(Mono<RemoveIssueLabelRequest> q) {
    return errors(q.flatMap(r -> metadata.detach(r.getBody())));
  }

  @Override
  public Mono<ListIssueLabelsResponse> listIssueLabels(Mono<ListIssueLabelsRequest> q) {
    return errors(q.flatMap(r -> metadata.issueLabels(r.getBody())));
  }

  @Override
  public Mono<WatchIssueResponse> watchIssue(Mono<WatchIssueRequest> q) {
    return errors(q.flatMap(r -> metadata.watch(r.getBody())));
  }

  @Override
  public Mono<UnwatchIssueResponse> unwatchIssue(Mono<UnwatchIssueRequest> q) {
    return errors(q.flatMap(r -> metadata.unwatch(r.getBody())));
  }

  @Override
  public Mono<ListIssueWatchersResponse> listIssueWatchers(Mono<ListIssueWatchersRequest> q) {
    return errors(q.flatMap(r -> metadata.watchers(r.getBody())));
  }

  @Override
  public Mono<GetIssueWatchStateResponse> getIssueWatchState(Mono<GetIssueWatchStateRequest> q) {
    return errors(q.flatMap(r -> metadata.state(r.getBody())));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
