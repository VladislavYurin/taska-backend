package ru.taska.transport.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.PreviewInvitationRequest;
import ru.taska.api.auth.v1.PreviewInvitationResponse;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.oauth.v1.ExchangeOAuthRequest;
import ru.taska.api.oauth.v1.ExchangeOAuthResponse;
import ru.taska.api.oauth.v1.ReactorOAuthServiceGrpc;
import ru.taska.api.oauth.v1.OAuthSignup;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.InvitationPreviewRequestDto;
import ru.taska.domain.dto.OAuthExchangeRequestDto;
import ru.taska.mapper.AuthMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Response values must survive a service-first rollout without losing a consumed one-time code. */
class Tas443OpenResponseValuesTest {
    private final GatewayContext context = new GatewayContext("tas443", "test", null);

    @Test
    void forwardsFutureInvitationKindWithoutEnumConversion() {
        var stub = mock(ReactorAuthServiceGrpc.ReactorAuthServiceStub.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.previewInvitation(any(PreviewInvitationRequest.class))).thenReturn(Mono.just(
                PreviewInvitationResponse.newBuilder().setEmail("person@example.invalid").setDisplayName("Person")
                        .setKind("FUTURE_INVITATION").build()));
        var service = new GrpcClientProperties.Service(null, 0, Duration.ofSeconds(5));
        var client = new GrpcAuthServiceClient(stub, mock(AuthMapper.class),
                new GrpcClientProperties(service, null, null, null, null, null));
        var response = client.previewInvitation(Mono.just(new InvitationPreviewRequestDto().token("one-time")), context).block();
        assertThat(response.getKind()).isEqualTo("FUTURE_INVITATION");
        assertThat(JsonMapper.builder().build().writeValueAsString(response)).contains("FUTURE_INVITATION");
        verify(stub).previewInvitation(any(PreviewInvitationRequest.class));
    }

    @Test
    void forwardsFutureOAuthStatusAndProviderAfterSingleExchange() {
        var stub = mock(ReactorOAuthServiceGrpc.ReactorOAuthServiceStub.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.exchangeOAuth(any(ExchangeOAuthRequest.class))).thenReturn(Mono.just(
                ExchangeOAuthResponse.newBuilder().setStatus("FUTURE_STEP").setReturnTo("/projects")
                        .setSignup(OAuthSignup.newBuilder().setSignupToken("one-time-signup")
                                .setEmail("person@example.invalid").setProvider("FUTURE_PROVIDER")).build()));
        var response = new GrpcOAuthClient(stub, mock(AuthMapper.class))
                .exchange(new OAuthExchangeRequestDto().code("one-time-code"), "unknown", context).block();
        assertThat(response.getStatus()).isEqualTo("FUTURE_STEP");
        assertThat(response.getSignup().getProvider()).isEqualTo("FUTURE_PROVIDER");
        assertThat(response.getSignup().getSignupToken()).isEqualTo("one-time-signup");
        assertThat(JsonMapper.builder().build().writeValueAsString(response)).contains("FUTURE_STEP", "FUTURE_PROVIDER");
        verify(stub).exchangeOAuth(any(ExchangeOAuthRequest.class));
    }
}
