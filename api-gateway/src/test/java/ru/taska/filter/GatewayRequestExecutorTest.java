package ru.taska.filter;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.GatewayUserContext;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayRequestExecutor Tests")
class GatewayRequestExecutorTest {

    private static final String REQUEST_ID = "req-id";

    @Mock
    private RequestIdProvider requestIdProvider;
    @Mock
    private BearerTokenExtractor bearerTokenExtractor;
    @Mock
    private GrpcAuthServiceClient authServiceClient;
    @Mock
    private ContextMapper contextMapper;

    @InjectMocks
    private GatewayRequestExecutor executor;

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        when(requestIdProvider.resolve(any())).thenReturn(REQUEST_ID);
    }

    @Test
    @DisplayName("Незащищённый запрос: должен создать контекст с null userContext и nodeId='api-gateway'")
    void execute_unsecured_createsContextWithNullUserContext() {
        StepVerifier.create(executor.execute(exchange, false, Mono::just))
                .assertNext(ctx -> {
                    assertThat(ctx.requestId()).isEqualTo(REQUEST_ID);
                    assertThat(ctx.nodeId()).isEqualTo("api-gateway");
                    assertThat(ctx.userContext()).isNull();
                })
                .verifyComplete();

        verifyNoInteractions(bearerTokenExtractor, authServiceClient);
    }

    @Test
    @DisplayName("Защищённый запрос: должен извлечь токен, вызвать auth-сервис и заполнить userContext")
    void execute_secured_validToken_populatesUserContext() {
        var securedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build()
        );
        when(bearerTokenExtractor.extract(securedExchange, REQUEST_ID)).thenReturn("valid-token");

        ValidateAccessTokenResponse grpcResponse = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder()
                        .setUserId("user-1")
                        .setLogin("testuser")
                        .setEmail("test@example.com")
                        .setDisplayName("Test User")
                        .setStatus(UserStatus.USER_STATUS_ACTIVE)
                        .build())
                .build();
        when(authServiceClient.validateAccessToken(eq(REQUEST_ID), eq("api-gateway"), eq("valid-token")))
                .thenReturn(Mono.just(grpcResponse));

        GatewayUserContext userCtx = new GatewayUserContext(
                "user-1", "testuser", "test@example.com", "Test User", UserStatus.USER_STATUS_ACTIVE);
        when(contextMapper.mapToGatewayUserContext(any())).thenReturn(userCtx);

        StepVerifier.create(executor.execute(securedExchange, true, Mono::just))
                .assertNext(ctx -> {
                    assertThat(ctx.requestId()).isEqualTo(REQUEST_ID);
                    assertThat(ctx.nodeId()).isEqualTo("api-gateway");
                    assertThat(ctx.userContext()).isSameAs(userCtx);
                })
                .verifyComplete();

        verify(bearerTokenExtractor).extract(securedExchange, REQUEST_ID);
        verify(authServiceClient).validateAccessToken(REQUEST_ID, "api-gateway", "valid-token");
    }

    @Test
    @DisplayName("Защищённый запрос: при отсутствии токена ошибка должна propagate как Mono.error")
    void execute_secured_missingToken_propagatesError() {
        ResponseStatusException authError = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token");
        when(bearerTokenExtractor.extract(exchange, REQUEST_ID)).thenThrow(authError);

        StepVerifier.create(executor.execute(exchange, true, Mono::just))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(authError));

        verifyNoInteractions(authServiceClient);
    }

    @Test
    @DisplayName("Защищённый запрос: gRPC-ошибка должна propagate как Mono.error")
    void execute_secured_grpcError_propagatesError() {
        var securedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer some-token")
                        .build()
        );
        when(requestIdProvider.resolve(securedExchange)).thenReturn(REQUEST_ID);
        when(bearerTokenExtractor.extract(securedExchange, REQUEST_ID)).thenReturn("some-token");

        StatusRuntimeException grpcError = Status.UNAUTHENTICATED
                .withDescription("Token expired")
                .asRuntimeException();
        when(authServiceClient.validateAccessToken(REQUEST_ID, "api-gateway", "some-token"))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(executor.execute(securedExchange, true, Mono::just))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(grpcError));
    }

    @Test
    @DisplayName("Ошибка в action должна propagate как Mono.error")
    void execute_actionThrowsException_propagatesError() {
        RuntimeException actionError = new RuntimeException("Action failed");

        StepVerifier.create(executor.execute(exchange, false, ctx -> Mono.error(actionError)))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(actionError));
    }

    @Test
    @DisplayName("Незащищённый запрос: action должен получить GatewayContext с корректным requestId")
    void execute_unsecured_actionReceivesContextWithCorrectRequestId() {
        StepVerifier.create(executor.execute(exchange, false, ctx -> Mono.just(ctx.requestId())))
                .expectNext(REQUEST_ID)
                .verifyComplete();
    }
}
