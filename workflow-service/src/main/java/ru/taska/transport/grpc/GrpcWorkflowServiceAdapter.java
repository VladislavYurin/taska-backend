package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequest;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequest;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.api.workflow.v1.WorkflowResponse;

@GrpcService
@RequiredArgsConstructor
public class GrpcWorkflowServiceAdapter  extends ReactorWorkflowServiceGrpc.WorkflowServiceImplBase {

    private final GrpcWorkflowService grpcWorkflowService;
    private final WorkflowMutationGrpcService mutations;
    private final ru.taska.service.WorkflowMutationResponse mutationResponse;
    private final WorkflowBindingGrpcService bindingOperations;
    private final WorkflowScopeCheckGrpcService scopeChecks;

    @Override
    public Mono<com.google.protobuf.Empty> checkProjectWorkflowScope(Mono<ru.taska.api.workflow.v1.CheckProjectWorkflowScopeRequest> request) {
        return scopeChecks.check(request).transform(GrpcExceptionHandler.withErrorHandling("checkProjectWorkflowScope"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.WorkflowBindingOperationResult> createProjectWorkflowWithBindings(Mono<CreateWorkflowRequest> request) {
        return bindingOperations.create(request).transform(GrpcExceptionHandler.withErrorHandling("createProjectWorkflowWithBindings"));
    }
    @Override
    public Mono<ru.taska.api.workflow.v1.WorkflowBindingOperationResult> updateProjectWorkflowWithBindings(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return bindingOperations.update(request).transform(GrpcExceptionHandler.withErrorHandling("updateProjectWorkflowWithBindings"));
    }

    @Override
    public Mono<WorkflowResponse> getWorkflowForProject(Mono<GetWorkflowForProjectRequest> request) {
       return grpcWorkflowService.getWorkflowForProject(request)
               .transform(GrpcExceptionHandler.withErrorHandling("getWorkflowForProject"));
    }
    @Override
    public Mono<ValidateTransitionResponse> validateTransition(Mono<ValidateTransitionRequest> request) {
        return grpcWorkflowService.validateTransition(request)
                .transform(GrpcExceptionHandler.withErrorHandling("validateTransition"));
    }
    @Override
    public Mono<WorkflowResponse> createWorkflow(Mono<CreateWorkflowRequest> request) {
        return request.flatMap(req -> mutationResponse.issueTypes(req.getHeader(),
                        req.getBody().getProjectId(), req.getBody().getActorUserId())
                .flatMap(types -> grpcWorkflowService.createWorkflow(Mono.just(req))
                        .flatMap(response -> mutationResponse.enrich(response, types))))
                .transform(GrpcExceptionHandler.withErrorHandling("createWorkflow"));
    }
    @Override
    public Mono<WorkflowResponse> addWorkflowStatus(Mono<AddWorkflowStatusRequest> request) {
        return request.flatMap(req -> mutationResponse.issueTypes(req.getHeader(),
                        req.getBody().getProjectId(), req.getBody().getActorUserId())
                .flatMap(types -> grpcWorkflowService.addWorkflowStatus(Mono.just(req))
                        .flatMap(response -> mutationResponse.enrich(response, types))))
                .transform(GrpcExceptionHandler.withErrorHandling("addWorkflowStatus"));
    }
    @Override
    public Mono<WorkflowResponse> addWorkflowTransition(Mono<AddWorkflowTransitionRequest> request) {
        return request.flatMap(req -> mutationResponse.issueTypes(req.getHeader(),
                        req.getBody().getProjectId(), req.getBody().getActorUserId())
                .flatMap(types -> grpcWorkflowService.addWorkflowTransition(Mono.just(req))
                        .flatMap(response -> mutationResponse.enrich(response, types))))
                .transform(GrpcExceptionHandler.withErrorHandling("addWorkflowTransition"));
    }
    @Override
    public Mono<ListProjectWorkflowsResponse> listProjectWorkflows(Mono<ListProjectWorkflowsRequest> request) {
        return grpcWorkflowService.listProjectWorkflows(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listProjectWorkflows"));
    }


    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> updateWorkflow(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.UPDATE_WORKFLOW, request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateWorkflow"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> updateWorkflowStatus(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.UPDATE_STATUS, request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateWorkflowStatus"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> deleteWorkflowStatus(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.DELETE_STATUS, request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteWorkflowStatus"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> updateWorkflowTransition(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.UPDATE_TRANSITION, request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateWorkflowTransition"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> deleteWorkflowTransition(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.DELETE_TRANSITION, request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteWorkflowTransition"));
    }

    @Override
    public Mono<ru.taska.api.workflow.v1.ProjectWorkflow> deleteProjectWorkflow(Mono<ru.taska.api.workflow.v1.WorkflowMutationRequest> request) {
        return mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.DELETE_WORKFLOW, request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteProjectWorkflow"));
    }
}
