package ru.taska.error;

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
import ru.taska.filter.RequestIdProvider;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayWebExceptionHandler Tests")
class GatewayWebExceptionHandlerTest {

    private static final String REQUEST_ID = "req-id";

    @Mock
    private GatewayErrorHandler errorHandler;
    @Mock
    private RequestIdProvider requestIdProvider;

    @InjectMocks
    private GatewayWebExceptionHandler handler;

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        when(requestIdProvider.resolve(exchange)).thenReturn(REQUEST_ID);
    }

    @Test
    @DisplayName("Должен делегировать обработку ошибки в GatewayErrorHandler с правильным requestId")
    void handle_delegatesToErrorHandler() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token");
        when(errorHandler.handleError(eq(exchange), eq(ex), eq(REQUEST_ID))).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(exchange, ex)).verifyComplete();

        verify(requestIdProvider).resolve(exchange);
        verify(errorHandler).handleError(exchange, ex, REQUEST_ID);
    }

    @Test
    @DisplayName("Должен возвращать Mono.error, если GatewayErrorHandler вернул ошибку")
    void handle_errorHandlerFails_propagatesError() {
        RuntimeException ex = new RuntimeException("Unexpected");
        RuntimeException serializationError = new RuntimeException("Serialization failed");
        when(errorHandler.handleError(eq(exchange), eq(ex), eq(REQUEST_ID)))
                .thenReturn(Mono.error(serializationError));

        StepVerifier.create(handler.handle(exchange, ex))
                .verifyErrorSatisfies(e -> e.equals(serializationError));
    }
}
