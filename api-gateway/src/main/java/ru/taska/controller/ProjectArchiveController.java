package ru.taska.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ProjectArchiveApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcProjectArchiveClient;

@RestController
@RequiredArgsConstructor
public class ProjectArchiveController implements ProjectArchiveApi {
    private final GatewayRequestExecutor executor;
    private final GrpcProjectArchiveClient client;

    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> archiveProject(UUID projectId, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context -> client.archive(projectId, context))
                .map(ResponseEntity::ok);
    }
}
