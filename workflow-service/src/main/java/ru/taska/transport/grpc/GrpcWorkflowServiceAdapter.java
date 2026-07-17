package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.api.workflow.v1.WorkflowResponse;

@GrpcService
@RequiredArgsConstructor
public class GrpcWorkflowServiceAdapter  extends ReactorWorkflowServiceGrpc.WorkflowServiceImplBase {

    private final GrpcWorkflowService grpcWorkflowService;

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
        return grpcWorkflowService.createWorkflow(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createWorkflow"));
    }

}
