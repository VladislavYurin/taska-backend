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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.taska.domain.EndpointSecurity.PROTECTED;
import static ru.taska.domain.EndpointSecurity.PUBLIC;

@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayContextFactory Tests")
class GatewayContextFactoryTest {

    private static final String REQUEST_ID = "req-id";
    private static final String NODE_ID = "api-gateway";

    @Mock
    private BearerTokenExtractor bearerTokenExtractor;
    @Mock
    private GrpcAuthServiceClient authServiceClient;
    @Mock
    private ContextMapper contextMapper;

    @InjectMocks
    private GatewayContextFactory factory;

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(factory, "nodeId", NODE_ID);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    @Test
    @DisplayName("PUBLIC: должен вернуть контекст с null userContext без обращения к auth-сервису")
    void buildContext_public_returnsContextWithNullUserContext() {
        StepVerifier.create(factory.buildContext(REQUEST_ID, exchange, PUBLIC))
                .assertNext(ctx -> {
                    assertThat(ctx.requestId()).isEqualTo(REQUEST_ID);
                    assertThat(ctx.nodeId()).isEqualTo(NODE_ID);
                    assertThat(ctx.userContext()).isNull();
                })
                .verifyComplete();

        verifyNoInteractions(bearerTokenExtractor, authServiceClient, contextMapper);
    }

    @Test
    @DisplayName("PROTECTED: валидный токен — должен извлечь токен, вызвать auth-сервис и заполнить userContext")
    void buildContext_protected_validToken_populatesUserContext() {
        when(bearerTokenExtractor.extract(exchange, REQUEST_ID)).thenReturn("valid-token");

        ValidateAccessTokenResponse grpcResponse = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder()
                        .setUserId("user-1")
                        .setLogin("testuser")
                        .setEmail("test@example.com")
                        .setDisplayName("Test User")
                        .setStatus(UserStatus.USER_STATUS_ACTIVE)
                        .build())
                .build();
        when(authServiceClient.validateAccessToken(REQUEST_ID, NODE_ID, "valid-token"))
                .thenReturn(Mono.just(grpcResponse));

        GatewayUserContext userCtx = new GatewayUserContext(
                "user-1", "testuser", "test@example.com", "Test User", GatewayUserStatus.ACTIVE);
        when(contextMapper.mapToGatewayUserContext(grpcResponse.getUserContext())).thenReturn(userCtx);

        StepVerifier.create(factory.buildContext(REQUEST_ID, exchange, PROTECTED))
                .assertNext(ctx -> {
                    assertThat(ctx.requestId()).isEqualTo(REQUEST_ID);
                    assertThat(ctx.nodeId()).isEqualTo(NODE_ID);
                    assertThat(ctx.userContext()).isSameAs(userCtx);
                })
                .verifyComplete();

        verify(bearerTokenExtractor).extract(exchange, REQUEST_ID);
        verify(authServiceClient).validateAccessToken(REQUEST_ID, NODE_ID, "valid-token");
        verify(contextMapper).mapToGatewayUserContext(grpcResponse.getUserContext());
    }

    @Test
    @DisplayName("PROTECTED: отсутствует токен — ошибка BearerTokenExtractor должна передаваться как Mono.error")
    void buildContext_protected_missingToken_propagatesError() {
        ResponseStatusException authError = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization header is missing");
        when(bearerTokenExtractor.extract(exchange, REQUEST_ID)).thenThrow(authError);

        StepVerifier.create(factory.buildContext(REQUEST_ID, exchange, PROTECTED))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(authError));

        verifyNoInteractions(authServiceClient, contextMapper);
    }

    @Test
    @DisplayName("PROTECTED: gRPC-ошибка auth-сервиса должна передаваться как Mono.error")
    void buildContext_protected_grpcError_propagatesError() {
        when(bearerTokenExtractor.extract(exchange, REQUEST_ID)).thenReturn("some-token");

        StatusRuntimeException grpcError = Status.UNAUTHENTICATED
                .withDescription("Token expired")
                .asRuntimeException();
        when(authServiceClient.validateAccessToken(REQUEST_ID, NODE_ID, "some-token"))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(factory.buildContext(REQUEST_ID, exchange, PROTECTED))
                .verifyErrorSatisfies(e -> assertThat(e).isSameAs(grpcError));

        verifyNoInteractions(contextMapper);
    }
}
