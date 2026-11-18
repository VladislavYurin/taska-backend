package ru.taska.controller;

import java.time.OffsetDateTime;
import java.util.UUID;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.*;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcProjectArchiveClient;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@WebFluxTest(controllers=ProjectArchiveController.class)
@Import({GatewayRequestExecutor.class,GatewayContextFactory.class,RequestIdProvider.class,
        BearerTokenExtractor.class,GatewayErrorHandler.class,RestErrorMapper.class})
class ProjectArchiveControllerTest {
    @Autowired WebTestClient http;
    @Autowired GatewayErrorHandler errors;
    @MockitoBean GrpcProjectArchiveClient client;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;
    private final UUID project=UUID.randomUUID(),user=UUID.randomUUID();

    @Test
    void anonymousCannotArchive() {
        http.delete().uri(url()).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(client);
    }

    @Test
    void repeatedArchiveReturnsProjectAndArchivedAt() {
        authenticate();
        var response=new ProjectResponseDto().id(project.toString()).projectKey("BUS").name("Business")
                .archivedAt(OffsetDateTime.parse("2026-10-23T00:00:00Z"));
        when(client.archive(eq(project),any())).thenReturn(Mono.just(response));
        for(int i=0;i<2;i++) http.delete().uri(url()).header(HttpHeaders.AUTHORIZATION,"Bearer token").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.archivedAt").exists().jsonPath("$.id").isEqualTo(project.toString());
        verify(client,times(2)).archive(eq(project),any());
    }

    @Test
    void memberWithoutAdminReceives403() {
        authenticate();
        when(client.archive(any(),any())).thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));
        http.delete().uri(url()).header(HttpHeaders.AUTHORIZATION,"Bearer token").exchange().expectStatus().isForbidden();
    }

    @Test
    void malformedProjectIdIsRejected() {
        authenticate();
        http.delete().uri("/api/v1/projects/not-a-uuid").header(HttpHeaders.AUTHORIZATION,"Bearer token").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(client);
    }

    @Test
    void lifecycleForwardsVersionAndCanonicalKey() {
        authenticate();var key=UUID.randomUUID().toString();
        when(client.lifecycle(eq(project),eq(false),eq(7),eq(key),any()))
                .thenReturn(Mono.just(new ProjectResponseDto().id(project.toString()).version("8")));
        http.post().uri(url()+"/restore").header(HttpHeaders.AUTHORIZATION,"Bearer token")
                .header("If-Match","\"7\"").header("Idempotency-Key",key).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo("8");
        verify(client).lifecycle(eq(project),eq(false),eq(7),eq(key),any());
    }

    @Test
    void restoreRejectsMissingMalformedAndDuplicatePreconditionsBeforeRpc() {
        // Exercise native problem mapping while this controller slice keeps its isolated auth fixture.
        org.springframework.test.util.ReflectionTestUtils.setField(errors,"trustedEnabled",true);
        authenticate();
        http.post().uri(url()+"/restore").header(HttpHeaders.AUTHORIZATION,"Bearer token")
                .header("Idempotency-Key",UUID.randomUUID().toString()).exchange().expectStatus().isEqualTo(428);
        for(var version:new String[]{"7","W/\"7\"","\"-1\"","\"2147483648\""})
            http.post().uri(url()+"/restore").header(HttpHeaders.AUTHORIZATION,"Bearer token")
                    .header("If-Match",version).header("Idempotency-Key",UUID.randomUUID().toString())
                    .exchange().expectStatus().isBadRequest();
        http.post().uri(url()+"/restore").header(HttpHeaders.AUTHORIZATION,"Bearer token")
                .header("If-Match","\"7\"","\"8\"").header("Idempotency-Key",UUID.randomUUID().toString())
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(client);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreErrorProfile() {
        org.springframework.test.util.ReflectionTestUtils.setField(errors,"trustedEnabled",false);
    }

    private String url() {return "/api/v1/projects/"+project;}
    private void authenticate() {
        when(auth.validateAccessToken(any(),any(),any())).thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(user.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder().userId(user.toString())
                .email("user@example.com").status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
    }
}
