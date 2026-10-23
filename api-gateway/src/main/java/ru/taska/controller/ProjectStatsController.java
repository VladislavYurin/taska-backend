package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ProjectStatsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.ListProjectStatsDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.impl.ProjectStatsService;

@RestController
@RequiredArgsConstructor
public class ProjectStatsController implements ProjectStatsApi {
    private final GatewayRequestExecutor executor;
    private final ProjectStatsService service;

    @Override
    public Mono<ResponseEntity<ListProjectStatsDto>> getProjectStats(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, service::getProjectStats).map(ResponseEntity::ok);
    }
}
