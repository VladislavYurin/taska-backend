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
    public Mono<ResponseEntity<ProjectResponseDto>> archiveProject(UUID projectId, String ifMatch, String idempotencyKey, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context -> {
            Integer version = version(exchange, false);
            String key = key(exchange, false);
            return version == null && key == null ? client.archive(projectId, context)
                    : client.lifecycle(projectId, true, version, key, context);
        })
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> restoreProject(UUID projectId, String ifMatch, String idempotencyKey, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,
                context -> client.lifecycle(projectId, false, version(exchange, true), key(exchange, true), context))
                .map(ResponseEntity::ok);
    }

    private static Integer version(ServerWebExchange exchange, boolean required) {
        var values = exchange.getRequest().getHeaders().getOrEmpty("If-Match");
        if (values.isEmpty()) {
            if (!required) return null;
            throw io.grpc.Status.FAILED_PRECONDITION.withDescription("Project version required")
                    .asRuntimeException(mapper.GrpcErrorDetails.encode(java.util.Map.of("code","VERSION_CONFLICT")));
        }
        if (values.size() != 1 || !values.getFirst().matches("\"(0|[1-9][0-9]{0,9})\"")) throw invalid();
        try { return Integer.valueOf(values.getFirst().substring(1,values.getFirst().length()-1)); }
        catch (NumberFormatException error) { throw invalid(); }
    }

    private static String key(ServerWebExchange exchange, boolean required) {
        var values = exchange.getRequest().getHeaders().getOrEmpty("Idempotency-Key");
        if (values.isEmpty() && !required) return null;
        if (values.size()!=1) throw invalid();
        try { if (!UUID.fromString(values.getFirst()).toString().equals(values.getFirst())) throw invalid(); }
        catch (IllegalArgumentException error) { throw invalid(); }
        return values.getFirst();
    }

    private static io.grpc.StatusRuntimeException invalid() {
        return io.grpc.Status.INVALID_ARGUMENT.withDescription("Invalid lifecycle precondition").asRuntimeException();
    }
}
