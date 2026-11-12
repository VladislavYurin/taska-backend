package ru.taska.workflowstorage;

import com.google.protobuf.Empty;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.service.WorkflowMutationService.Operation;

/** Same wire contract as the legacy profile; only verified native contexts enter this adapter. */
@GrpcService
public class TenantWorkflowGrpc extends ReactorWorkflowServiceGrpc.WorkflowServiceImplBase {
  private final TenantWorkflowApi api;

  public TenantWorkflowGrpc(TenantWorkflowApi api) {
    this.api = api;
  }

  @Override
  public Mono<WorkflowResponse> getWorkflowForProject(Mono<GetWorkflowForProjectRequest> q) {
    return errors(q.flatMap(r -> api.get(r.getBody())));
  }

  @Override
  public Mono<ListProjectWorkflowsResponse> listProjectWorkflows(
      Mono<ListProjectWorkflowsRequest> q) {
    return errors(q.flatMap(r -> api.list(r.getBody())));
  }

  @Override
  public Mono<Empty> checkProjectWorkflowScope(Mono<CheckProjectWorkflowScopeRequest> q) {
    return errors(q.flatMap(r -> api.scope(r).thenReturn(Empty.getDefaultInstance())));
  }

  @Override
  public Mono<ValidateTransitionResponse> validateTransition(Mono<ValidateTransitionRequest> q) {
    return errors(
        q.flatMap(
            r ->
                api.validate(r.getBody())
                    .map(
                        body ->
                            ValidateTransitionResponse.newBuilder()
                                .setHeader(r.getHeader())
                                .setBody(body)
                                .build())));
  }

  @Override
  public Mono<WorkflowResponse> createWorkflow(Mono<CreateWorkflowRequest> q) {
    return errors(q.flatMap(r -> api.create(r.getBody())).map(ProjectWorkflow::getWorkflow));
  }

  @Override
  public Mono<WorkflowBindingOperationResult> createProjectWorkflowWithBindings(
      Mono<CreateWorkflowRequest> q) {
    return errors(
        q.flatMap(r -> api.create(r.getBody()))
            .map(result -> WorkflowBindingOperationResult.newBuilder().setResult(result).build()));
  }

  @Override
  public Mono<WorkflowBindingOperationResult> updateProjectWorkflowWithBindings(
      Mono<WorkflowMutationRequest> q) {
    return errors(
        q.flatMap(r -> api.mutate(Operation.UPDATE_WORKFLOW, r))
            .map(result -> WorkflowBindingOperationResult.newBuilder().setResult(result).build()));
  }

  @Override
  public Mono<WorkflowResponse> addWorkflowStatus(Mono<AddWorkflowStatusRequest> q) {
    return errors(q.flatMap(r -> api.addStatus(r.getBody())));
  }

  @Override
  public Mono<WorkflowResponse> addWorkflowTransition(Mono<AddWorkflowTransitionRequest> q) {
    return errors(q.flatMap(r -> api.addTransition(r.getBody())));
  }

  @Override
  public Mono<ProjectWorkflow> updateWorkflow(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.UPDATE_WORKFLOW);
  }

  @Override
  public Mono<ProjectWorkflow> updateWorkflowStatus(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.UPDATE_STATUS);
  }

  @Override
  public Mono<ProjectWorkflow> deleteWorkflowStatus(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.DELETE_STATUS);
  }

  @Override
  public Mono<ProjectWorkflow> updateWorkflowTransition(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.UPDATE_TRANSITION);
  }

  @Override
  public Mono<ProjectWorkflow> deleteWorkflowTransition(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.DELETE_TRANSITION);
  }

  @Override
  public Mono<ProjectWorkflow> deleteProjectWorkflow(Mono<WorkflowMutationRequest> q) {
    return mutation(q, Operation.DELETE_WORKFLOW);
  }

  private Mono<ProjectWorkflow> mutation(Mono<WorkflowMutationRequest> q, Operation op) {
    return errors(q.flatMap(r -> api.mutate(op, r)));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
