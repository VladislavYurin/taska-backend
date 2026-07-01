package ru.taska.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.GatewayContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.taska.domain.EndpointSecurity.PROTECTED;
import static ru.taska.domain.EndpointSecurity.PUBLIC;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayRequestExecutor Tests")
class GatewayRequestExecutorTest {

    private static final String REQUEST_ID = "req-id";

    @Mock
    private RequestIdProvider requestIdProvider;
    @Mock
    private GatewayContextFactory contextFactory;

    @InjectMocks
    private GatewayRequestExecutor executor;

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        when(requestIdProvider.resolve(any())).thenReturn(REQUEST_ID);
    }

    @Test
    @DisplayName("PUBLIC: должен вызвать фабрику с правильными аргументами и передать контекст в action")
    void execute_public_delegatesToFactoryAndPassesContextToAction() {
        var ctx = new GatewayContext(REQUEST_ID, "api-gateway", null);
        when(contextFactory.buildContext(eq(REQUEST_ID), eq(exchange), eq(PUBLIC))).thenReturn(Mono.just(ctx));

        StepVerifier.create(executor.execute(exchange, PUBLIC, Mono::just))
                .assertNext(result -> assertThat(result).isSameAs(ctx))
                .verifyComplete();

        verify(contextFactory).buildContext(REQUEST_ID, exchange, PUBLIC);
    }

    @Test
    @DisplayName("PROTECTED: должен вызвать фабрику с правильными аргументами и передать контекст в action")
    void execute_protected_delegatesToFactoryAndPassesContextToAction() {
        var ctx = new GatewayContext(REQUEST_ID, "api-gateway", null);
        when(contextFactory.buildContext(eq(REQUEST_ID), eq(exchange), eq(PROTECTED))).thenReturn(Mono.just(ctx));

        StepVerifier.create(executor.execute(exchange, PROTECTED, Mono::just))
                .assertNext(result -> assertThat(result).isSameAs(ctx))
                .verifyComplete();

        verify(contextFactory).buildContext(REQUEST_ID, exchange, PROTECTED);
    }

    @Test
    @DisplayName("Ошибка из фабрики должна передаваться как Mono.error")
    void execute_factoryError_propagatesError() {
        ResponseStatusException factoryError = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token");
        when(contextFactory.buildContext(eq(REQUEST_ID), any(), eq(PROTECTED))).thenReturn(Mono.error(factoryError));

        StepVerifier.create(executor.execute(exchange, PROTECTED, Mono::just))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(factoryError));
    }

    @Test
    @DisplayName("Ошибка в action должна передаваться как Mono.error")
    void execute_actionThrowsException_propagatesError() {
        var ctx = new GatewayContext(REQUEST_ID, "api-gateway", null);
        when(contextFactory.buildContext(eq(REQUEST_ID), any(), eq(PUBLIC))).thenReturn(Mono.just(ctx));
        RuntimeException actionError = new RuntimeException("Action failed");

        StepVerifier.create(executor.execute(exchange, PUBLIC, c -> Mono.error(actionError)))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(actionError));
    }
}
