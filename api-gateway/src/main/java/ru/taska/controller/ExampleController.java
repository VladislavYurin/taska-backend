package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.filter.GatewayRequestExecutor;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class ExampleController {

    private final GatewayRequestExecutor executor;

    @GetMapping("/me")
    public Mono<GatewayUserContext> testEndpoint(ServerWebExchange exchange) {
        return executor.execute(exchange, true, context -> Mono.just(context.userContext()));
    }

    @GetMapping("/public")
    public Mono<GatewayContext> testNotSecuredEndpoint(ServerWebExchange exchange) {
        return executor.execute(exchange, false, Mono::just);
    }
}
