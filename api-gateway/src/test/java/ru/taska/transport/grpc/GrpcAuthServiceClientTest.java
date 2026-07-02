package ru.taska.transport.grpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcAuthServiceClient Tests")
class GrpcAuthServiceClientTest {

    private static final String REQUEST_ID = "req-id";
    private static final String NODE_ID = "api-gateway";
    private static final String ACCESS_TOKEN = "access-token";

    @Mock
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub;

    @InjectMocks
    private GrpcAuthServiceClient client;

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
        when(authServiceStub.validateAccessToken(any(ValidateAccessTokenRequest.class))).thenReturn(Mono.just(response));

        StepVerifier.create(client.validateAccessToken(REQUEST_ID, NODE_ID, ACCESS_TOKEN))
                .assertNext(res -> {
                    assertThat(res.getUserContext().getUserId()).isEqualTo("user-123");
                    assertThat(res.getUserContext().getLogin()).isEqualTo("testuser");
                    assertThat(res.getUserContext().getEmail()).isEqualTo("test@example.com");
                    assertThat(res.getUserContext().getDisplayName()).isEqualTo("Test User");
                    assertThat(res.getUserContext().getStatus()).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
                })
                .verifyComplete();

        ArgumentCaptor<ValidateAccessTokenRequest> captor = ArgumentCaptor.forClass(ValidateAccessTokenRequest.class);
        verify(authServiceStub).validateAccessToken(captor.capture());

        ValidateAccessTokenRequest request = captor.getValue();
        assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        assertThat(request.getBody().getAccessToken()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("Должен пробросить ошибку, если gRPC-стаб вернул Mono.error")
    void validateAccessToken_stubReturnsError_propagatesError() {
        RuntimeException grpcError = new RuntimeException("gRPC connection failure");
        when(authServiceStub.validateAccessToken(any(ValidateAccessTokenRequest.class))).thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.validateAccessToken(REQUEST_ID, NODE_ID, ACCESS_TOKEN))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && "gRPC connection failure".equals(e.getMessage()))
                .verify();
    }
}
