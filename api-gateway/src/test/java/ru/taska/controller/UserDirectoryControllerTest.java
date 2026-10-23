package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.*;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcUserDirectoryClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@WebFluxTest(controllers = UserDirectoryController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class})
class UserDirectoryControllerTest {
    @Autowired
    private WebTestClient client;
    @MockitoBean
    private GrpcAuthServiceClient auth;
    @MockitoBean
    private ContextMapper contextMapper;
    @MockitoBean
    private GrpcUserDirectoryClient directory;
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void adminCreatesInvitationAndResponseContainsNoTokenOrActivationUrl() {
        authenticated(GlobalRole.GLOBAL_ADMIN);
        when(directory.invite(any(), any())).thenReturn(Mono.just(new GrpcUserDirectoryClient.InvitationResult(true, invitation(true))));
        client.post().uri("/api/v1/auth/invitations").header("Authorization", "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\" New@Example.test \",\"displayName\":\"New person\"}")
                .exchange().expectStatus().isCreated().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.userId").isEqualTo(TARGET.toString())
                .jsonPath("$.status").isEqualTo("INVITED").jsonPath("$.deliveryQueued").isEqualTo(true)
                .jsonPath("$.token").doesNotExist().jsonPath("$.activationUrl").doesNotExist();
        var body = ArgumentCaptor.forClass(InvitationRequestDto.class);
        verify(directory).invite(body.capture(), any());
        assertThat(body.getValue().getEmail()).isEqualTo(" New@Example.test ");
        assertThat(body.getValue().getDisplayName()).isEqualTo("New person");
    }

    @Test
    void retryReturns200WithTheSameUserAndNoNewDelivery() {
        authenticated(GlobalRole.GLOBAL_ADMIN);
        when(directory.invite(any(), any())).thenReturn(Mono.just(new GrpcUserDirectoryClient.InvitationResult(false, invitation(false))));
        client.post().uri("/api/v1/auth/invitations").header("Authorization", "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"email\":\"new@example.test\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.userId").isEqualTo(TARGET.toString())
                .jsonPath("$.deliveryQueued").isEqualTo(false);
    }

    @Test
    void ordinaryUserGets403AndNoInvitationRpcIsCalled() {
        authenticated(GlobalRole.USER);
        client.post().uri("/api/v1/auth/invitations").header("Authorization", "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"email\":\"new@example.test\"}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(directory);
    }

    @Test
    void unauthenticatedDirectoryRequestGets401() {
        client.get().uri("/api/v1/users?query=person").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(directory);
    }

    @Test
    void directoryReturnsMinimalPublicFieldsAndUsesPaginationDefaults() {
        authenticated(GlobalRole.USER);
        var item = new DirectoryUserDto();
        item.setId(TARGET);
        item.setDisplayName("Person");
        item.setEmail("person@example.test");
        var response = new UserDirectoryResponseDto();
        response.setItems(List.of(item));
        response.setHasMore(false);
        when(directory.search(eq("person"), eq(0), eq(20), any())).thenReturn(Mono.just(response));
        client.get().uri("/api/v1/users?query=person").header("Authorization", "Bearer token")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.items[0].id").isEqualTo(TARGET.toString())
                .jsonPath("$.items[0].displayName").isEqualTo("Person")
                .jsonPath("$.items[0].email").isEqualTo("person@example.test")
                .jsonPath("$.items[0].globalRole").doesNotExist().jsonPath("$.items[0].token").doesNotExist()
                .jsonPath("$.hasMore").isEqualTo(false);
    }

    @Test
    void malformedPaginationAndMissingQueryAreRejectedBeforeRpc() {
        for (String uri : List.of("/api/v1/users", "/api/v1/users?query=ab", "/api/v1/users?query=person&page=-1",
                "/api/v1/users?query=person&pageSize=51")) {
            client.get().uri(uri).header("Authorization", "Bearer token").exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(directory);
    }

    @Test
    void businessConflictAndResendThrottleKeepTheirHttpStatuses() {
        authenticated(GlobalRole.GLOBAL_ADMIN);
        when(directory.invite(any(), any())).thenReturn(Mono.error(Status.ALREADY_EXISTS.withDescription("Account exists").asRuntimeException()));
        client.post().uri("/api/v1/auth/invitations").header("Authorization", "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"email\":\"new@example.test\"}")
                .exchange().expectStatus().isEqualTo(409);
        when(directory.invite(any(), any())).thenReturn(Mono.error(mapper.GrpcExceptionMapper.toStatusRuntimeException(
                new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.RESOURCE_EXHAUSTED,
                        "Retry later", java.util.Map.of("retryAfterSeconds", "45")))));
        client.post().uri("/api/v1/auth/invitations").header("Authorization", "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"email\":\"new@example.test\",\"resend\":true}")
                .exchange().expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "45")
                .expectBody().jsonPath("$.details.retryAfterSeconds").isEqualTo("45");
    }

    private InvitationResponseDto invitation(boolean queued) {
        var result = new InvitationResponseDto();
        result.setUserId(TARGET);
        result.setStatus(UserStatusDto.INVITED);
        result.setExpiresAt(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
        result.setDeliveryQueued(queued);
        return result;
    }

    private void authenticated(GlobalRole role) {
        var userContext = UserContext.newBuilder().setUserId(ACTOR).build();
        when(auth.validateAccessToken(any(), any(), eq("token")))
                .thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder().setUserContext(userContext).build()));
        when(contextMapper.mapToGatewayUserContext(userContext)).thenReturn(new GatewayUserContext(
                ACTOR, "actor@example.test", "Actor", GatewayUserStatus.ACTIVE, role));
    }
}
