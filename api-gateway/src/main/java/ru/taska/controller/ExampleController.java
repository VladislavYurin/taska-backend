package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.filter.GatewayRequestExecutor;

import static ru.taska.domain.EndpointSecurity.GLOBAL_ADMIN_REQUIRED;
import static ru.taska.domain.EndpointSecurity.PROTECTED;
import static ru.taska.domain.EndpointSecurity.PUBLIC;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class ExampleController {

    private final GatewayRequestExecutor executor;

    @GetMapping("/me")
    public Mono<GatewayContext> testEndpoint(ServerWebExchange exchange) {
        return executor.execute(exchange, PROTECTED, Mono::just);
    }

    @GetMapping("/public")
    public Mono<GatewayContext> testNotSecuredEndpoint(ServerWebExchange exchange) {
        return executor.execute(exchange, PUBLIC, Mono::just);
    }
}
