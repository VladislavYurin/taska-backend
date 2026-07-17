package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.LoginRequestDto;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.PasswordByTokenRequestDto;
import ru.taska.domain.dto.RefreshRequestDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.mapper.AuthMapper;

import java.time.Duration;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcAuthServiceClient Tests")
class GrpcAuthServiceClientTest {

    private static final String REQUEST_ID = "req-id";
    private static final String NODE_ID = "api-gateway";
    private static final String ACCESS_TOKEN = "access-token";

    @Mock
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub;
    @Mock
    private AuthMapper authMapper;
    @Mock
    private GrpcClientProperties properties;
    @Mock
    private GrpcClientProperties.Service authServiceProp;

    @InjectMocks
    private GrpcAuthServiceClient client;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        Mockito.when(properties.authService()).thenReturn(authServiceProp);
        Mockito.when(authServiceProp.deadlineDuration()).thenReturn(Duration.ofMillis(5000));
        Mockito.when(authServiceStub.withDeadlineAfter(ArgumentMatchers.any(Long.class),
                ArgumentMatchers.any())).thenReturn(authServiceStub);

        client = new GrpcAuthServiceClient(authServiceStub, authMapper, properties);
        context = new GatewayContext("req-123", "node-a", null);
    }

    @Test
    @DisplayName("Должен корректно сформировать gRPC-запрос и вернуть ответ от стаба")
    void validateAccessToken_validParams_buildsCorrectRequestAndReturnsResponse() {
        ValidateAccessTokenResponse response = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder()
                        .setUserId("user-123")
                        .setLogin("testuser")
                        .setEmail("test@example.com")
                        .setDisplayName("Test User")
                        .setStatus(UserStatus.USER_STATUS_ACTIVE)
                        .build())
                .build();
        Mockito.when(authServiceStub.validateAccessToken(ArgumentMatchers.any(ValidateAccessTokenRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.validateAccessToken(REQUEST_ID, NODE_ID, ACCESS_TOKEN))
                .assertNext(res -> {
                    Assertions.assertThat(res.getUserContext().getUserId()).isEqualTo("user-123");
                    Assertions.assertThat(res.getUserContext().getLogin()).isEqualTo("testuser");
                    Assertions.assertThat(res.getUserContext().getEmail()).isEqualTo("test@example.com");
                    Assertions.assertThat(res.getUserContext().getDisplayName()).isEqualTo("Test User");
                    Assertions.assertThat(res.getUserContext().getStatus()).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
                })
                .verifyComplete();

        ArgumentCaptor<ValidateAccessTokenRequest> captor = ArgumentCaptor.forClass(ValidateAccessTokenRequest.class);
        Mockito.verify(authServiceStub).validateAccessToken(captor.capture());

        ValidateAccessTokenRequest request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getAccessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("Должен пробросить ошибку, если gRPC-стаб вернул Mono.error")
    void validateAccessToken_stubReturnsError_propagatesError() {
        RuntimeException grpcError = new RuntimeException("gRPC connection failure");
        Mockito.when(authServiceStub.validateAccessToken(ArgumentMatchers.any(ValidateAccessTokenRequest.class)))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.validateAccessToken(REQUEST_ID, NODE_ID, ACCESS_TOKEN))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && "gRPC connection failure".equals(e.getMessage()))
                .verify();
    }

    @Test
    @DisplayName("Успешный login возвращает токена и данные авторизации")
    void login_Success() {
        LoginRequestDto requestDto = new LoginRequestDto();
        LoginRequest grpcRequest = LoginRequest.getDefaultInstance();
        LoginResponse grpcResponse = LoginResponse.newBuilder()
                .setAccessToken("valid-access-token")
                .setRefreshToken("valid-refresh-token")
                .setExpiresIn(3600)
                .build();
        LoginResponseDto expectedDto = new LoginResponseDto();

        Mockito.when(authMapper.toLoginGrpcRequest(ArgumentMatchers.any(LoginRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.login(grpcRequest)).thenReturn(Mono.just(grpcResponse));
        Mockito.when(authMapper.toLoginRestResponse(grpcResponse)).thenReturn(expectedDto);

        StepVerifier.create(client.login(Mono.just(requestDto), context))
                .expectNext(expectedDto)
                .verifyComplete();
    }

    @Test
    @DisplayName("Неверные credentials вызывают ошибку UNAUTHENTICATED")
    void login_InvalidCredentials_ThrowsUnauthenticated() {
        LoginRequestDto requestDto = new LoginRequestDto();
        LoginRequest grpcRequest = LoginRequest.getDefaultInstance();
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.UNAUTHENTICATED.withDescription("Invalid email or password")
        );

        Mockito.when(authMapper.toLoginGrpcRequest(ArgumentMatchers.any(LoginRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.login(grpcRequest)).thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.login(Mono.just(requestDto), context))
                .expectErrorMatches(throwable -> throwable instanceof StatusRuntimeException &&
                        ((StatusRuntimeException) throwable).getStatus().getCode() == Status.Code.UNAUTHENTICATED)
                .verify();
    }

    @Test
    @DisplayName("Успешная ротация refresh token возвращает новую пару токенов")
    void refresh_Success() {
        RefreshRequestDto requestDto = new RefreshRequestDto();
        RefreshRequest grpcRequest = RefreshRequest.getDefaultInstance();
        RefreshResponse grpcResponse = RefreshResponse.newBuilder()
                .setAccessToken("new-access-token")
                .setRefreshToken("new-refresh-token")
                .setExpiresIn(3600)
                .build();
        RefreshResponseDto expectedDto = new RefreshResponseDto();

        Mockito.when(authMapper.toRefreshGrpcRequest(ArgumentMatchers.any(RefreshRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.refresh(grpcRequest)).thenReturn(Mono.just(grpcResponse));
        Mockito.when(authMapper.toRefreshRestResponse(grpcResponse)).thenReturn(expectedDto);

        StepVerifier.create(client.refresh(Mono.just(requestDto), context))
                .expectNext(expectedDto)
                .verifyComplete();
    }

    @Test
    @DisplayName("Истёкший или невалидный refresh token вызывает ошибку UNAUTHENTICATED")
    void refresh_InvalidOrExpiredToken_ThrowsUnauthenticated() {
        RefreshRequestDto requestDto = new RefreshRequestDto();
        RefreshRequest grpcRequest = RefreshRequest.getDefaultInstance();
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.UNAUTHENTICATED.withDescription("Refresh token expired or blacklisted")
        );

        Mockito.when(authMapper.toRefreshGrpcRequest(ArgumentMatchers.any(RefreshRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.refresh(grpcRequest)).thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.refresh(Mono.just(requestDto), context))
                .expectErrorMatches(throwable -> throwable instanceof StatusRuntimeException &&
                        ((StatusRuntimeException) throwable).getStatus().getCode() == Status.Code.UNAUTHENTICATED)
                .verify();
    }

    @Test
    @DisplayName("Успешная установка пароля по инвайт-токену завершается успешно")
    void setPasswordByToken_Success() {
        PasswordByTokenRequestDto requestDto = new PasswordByTokenRequestDto();
        SetPasswordByTokenRequest grpcRequest = SetPasswordByTokenRequest.getDefaultInstance();

        Mockito.when(authMapper.toPasswordByTokenGrpcRequest(ArgumentMatchers.any(PasswordByTokenRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.setPasswordByToken(grpcRequest)).thenReturn(Mono.empty());

        StepVerifier.create(client.setPasswordByToken(Mono.just(requestDto), context))
                .verifyComplete();
    }

    @Test
    @DisplayName("Невалидный инвайт-токен вызывает ошибку INVALID_ARGUMENT")
    void setPasswordByToken_InvalidInviteToken_ThrowsInvalidArgument() {
        PasswordByTokenRequestDto requestDto = new PasswordByTokenRequestDto();
        SetPasswordByTokenRequest grpcRequest = SetPasswordByTokenRequest.getDefaultInstance();
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("Invitation token is invalid or has expired")
        );

        Mockito.when(authMapper.toPasswordByTokenGrpcRequest(ArgumentMatchers.any(PasswordByTokenRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.setPasswordByToken(grpcRequest)).thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.setPasswordByToken(Mono.just(requestDto), context))
                .expectErrorMatches(throwable -> throwable instanceof StatusRuntimeException &&
                        ((StatusRuntimeException) throwable).getStatus().getCode() == Status.Code.INVALID_ARGUMENT)
                .verify();
    }

    @Test
    @DisplayName("Слишком простой пароль вызывает ошибку INVALID_ARGUMENT")
    void setPasswordByToken_PasswordPolicyViolation_ThrowsInvalidArgument() {
        PasswordByTokenRequestDto requestDto = new PasswordByTokenRequestDto();
        SetPasswordByTokenRequest grpcRequest = SetPasswordByTokenRequest.getDefaultInstance();
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("Password must contain at least one digit and spec character")
        );

        Mockito.when(authMapper.toPasswordByTokenGrpcRequest(ArgumentMatchers.any(PasswordByTokenRequestDto.class),
                ArgumentMatchers.any(GatewayContext.class))).thenReturn(grpcRequest);
        Mockito.when(authServiceStub.setPasswordByToken(grpcRequest)).thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.setPasswordByToken(Mono.just(requestDto), context))
                .expectErrorMatches(throwable -> throwable instanceof StatusRuntimeException &&
                        ((StatusRuntimeException) throwable).getStatus().getCode() == Status.Code.INVALID_ARGUMENT)
                .verify();
    }
}

