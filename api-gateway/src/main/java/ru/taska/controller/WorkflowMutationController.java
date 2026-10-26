package ru.taska.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.WorkflowMutationApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcWorkflowMutationClient;
import ru.taska.transport.grpc.GrpcWorkflowMutationClient.Operation;

@RestController
@RequiredArgsConstructor
public class WorkflowMutationController implements WorkflowMutationApi {
    private final GatewayRequestExecutor executor;
    private final GrpcWorkflowMutationClient client;

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> updateProjectWorkflow(UUID projectId, UUID workflowId,
            Mono<PatchWorkflowRequestDto> body, ServerWebExchange exchange) {
        return execute(Operation.UPDATE_WORKFLOW, projectId, workflowId, null, null, body, null, null, exchange);
    }

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> updateWorkflowStatus(UUID projectId, UUID workflowId, UUID statusId,
            Mono<PatchWorkflowStatusRequestDto> body, ServerWebExchange exchange) {
        return execute(Operation.UPDATE_STATUS, projectId, workflowId, statusId, null, body, null, null, exchange);
    }

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> deleteWorkflowStatus(UUID projectId, UUID workflowId, UUID statusId,
            Integer version, String targetStatusKey, ServerWebExchange exchange) {
        return execute(Operation.DELETE_STATUS, projectId, workflowId, statusId, null, Mono.empty(), version, targetStatusKey, exchange);
    }

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> updateWorkflowTransition(UUID projectId, UUID workflowId, UUID transitionId,
            Mono<PatchWorkflowTransitionRequestDto> body, ServerWebExchange exchange) {
        return execute(Operation.UPDATE_TRANSITION, projectId, workflowId, null, transitionId, body, null, null, exchange);
    }

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> deleteWorkflowTransition(UUID projectId, UUID workflowId, UUID transitionId,
            Integer version, ServerWebExchange exchange) {
        return execute(Operation.DELETE_TRANSITION, projectId, workflowId, null, transitionId, Mono.empty(), version, null, exchange);
    }

    @Override
    public Mono<ResponseEntity<ProjectWorkflowDto>> deleteProjectWorkflow(UUID projectId, UUID workflowId,
            Mono<DeleteProjectWorkflowRequestDto> body, ServerWebExchange exchange) {
        return execute(Operation.DELETE_WORKFLOW, projectId, workflowId, null, null,
                body.defaultIfEmpty(new DeleteProjectWorkflowRequestDto()), null, null, exchange);
    }

    // WebFlux's typed optional @RequestBody still needs a JSON decoder. Route the
    // bodyless variant separately, and refuse any untyped bytes rather than ignore a mapping.
    @org.springframework.web.bind.annotation.DeleteMapping(
            value = WorkflowMutationApi.PATH_DELETE_PROJECT_WORKFLOW, headers = "!Content-Type")
    public Mono<ResponseEntity<ProjectWorkflowDto>> deleteProjectWorkflowWithoutBody(
            @org.springframework.web.bind.annotation.PathVariable("projectId") UUID projectId,
            @org.springframework.web.bind.annotation.PathVariable("workflowId") UUID workflowId,
            ServerWebExchange exchange) {
        Mono<Void> emptyBody = org.springframework.core.io.buffer.DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(buffer -> {
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use application/json for statusMappings"));
                });
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> emptyBody.then(Mono.defer(() ->
                        client.mutate(Operation.DELETE_WORKFLOW, projectId, workflowId, null, null,
                                new DeleteProjectWorkflowRequestDto(), null, null, c))))
                .map(ResponseEntity::ok);
    }

    private Mono<ResponseEntity<ProjectWorkflowDto>> execute(Operation operation, UUID project, UUID workflow, UUID status,
            UUID transition, Mono<? extends WorkflowPatchBody> body, Integer version, String target, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> body
                        .map(patch -> (WorkflowPatchBody) patch)
                        .defaultIfEmpty(new WorkflowPatchBody() {})
                        .flatMap(patch -> client.mutate(operation, project, workflow, status, transition, patch, version, target, c)))
                .map(ResponseEntity::ok);
    }
}
