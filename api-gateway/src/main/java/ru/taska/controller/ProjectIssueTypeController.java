package ru.taska.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ProjectIssueTypeApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcProjectIssueTypeClient;

@RestController
@RequiredArgsConstructor
public class ProjectIssueTypeController implements ProjectIssueTypeApi {
    private final GatewayRequestExecutor executor;
    private final GrpcProjectIssueTypeClient client;

    @Override
    public Mono<ResponseEntity<ListProjectIssueTypesDto>> listProjectIssueTypes(UUID projectId, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> client.list(projectId, c)).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<ProjectIssueTypeDto>> createProjectIssueType(UUID projectId,
            Mono<CreateProjectIssueTypeRequestDto> body, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> body.flatMap(b -> client.create(projectId, b, c)))
                .map(result -> ResponseEntity.status(201).body(result));
    }

    @Override
    public Mono<ResponseEntity<ProjectIssueTypeDto>> updateProjectIssueType(UUID projectId, String code,
            Mono<PatchProjectIssueTypeRequestDto> body, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> body.flatMap(b -> client.update(projectId, code, b, c)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<ListProjectIssueTypesDto>> reorderProjectIssueTypes(UUID projectId,
            Mono<ReorderProjectIssueTypesRequestDto> body, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> body.flatMap(b -> client.reorder(projectId, b, c)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Void>> deleteProjectIssueType(UUID projectId, String code,
            Mono<DeleteProjectIssueTypeRequestDto> body, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, c -> body
                .defaultIfEmpty(new DeleteProjectIssueTypeRequestDto())
                .flatMap(command -> client.delete(projectId, code, command, c)))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
