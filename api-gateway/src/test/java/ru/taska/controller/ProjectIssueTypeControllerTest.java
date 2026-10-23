package ru.taska.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import java.util.List;
import java.util.UUID;
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
import ru.taska.domain.dto.*;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcProjectIssueTypeClient;

@WebFluxTest(controllers = ProjectIssueTypeController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class})
class ProjectIssueTypeControllerTest {
    @Autowired WebTestClient http;
    @MockitoBean GrpcProjectIssueTypeClient client;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;
    private final UUID project = UUID.randomUUID(), user = UUID.randomUUID();

    @Test
    void anonymousCannotReadRegistry() {
        http.get().uri(url()).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(client);
    }

    @Test
    void memberCanReadCustomTypeMetadata() {
        authenticated();
        when(client.list(any(), any())).thenReturn(Mono.just(new ListProjectIssueTypesDto(List.of(type()))));
        http.get().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
                .expectStatus().isOk().expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.items[0].code").isEqualTo("REQUEST")
                .jsonPath("$.items[0].icon").isEqualTo("headset");
    }

    @Test
    void createReturns201AndPatchUpdatesMetadata() {
        authenticated();
        when(client.create(any(), any(), any())).thenReturn(Mono.just(type()));
        http.post().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"code\":\"REQUEST\",\"name\":\"Заявка\",\"color\":\"teal\",\"icon\":\"headset\"}")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").exchange().expectStatus().isCreated();
        when(client.update(any(), any(), any(), any())).thenReturn(Mono.just(type()));
        http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"name\":\"Заявка\"}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo("REQUEST");
    }

    @Test
    void explicitNullOrFractionalSortOrderDoesNotReachBackend() {
        authenticated();
        for (String body : List.of("{\"name\":null}", "{\"sortOrder\":1.5}", "{\"sortOrder\":2147483648}",
                "{\"name\":\"Name\",\"code\":\"NEW_CODE\"}")) {
            http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .bodyValue(body).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(client);
    }

    @Test
    void memberCannotModifyType() {
        authenticated();
        when(client.delete(any(), any(), any())).thenReturn(Mono.error(Status.PERMISSION_DENIED
                .withDescription("Project ADMIN role is required").asRuntimeException()));
        http.delete().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isForbidden();
    }

    private String url() { return "/api/v1/projects/" + project + "/issue-types"; }

    private ProjectIssueTypeDto type() {
        return new ProjectIssueTypeDto("REQUEST", "Заявка", ProjectIssueTypeColorDto.TEAL,
                ProjectIssueTypeIconDto.HEADSET, 40);
    }

    private void authenticated() {
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(Mono.just(ValidateAccessTokenResponse
                .newBuilder().setUserContext(UserContext.newBuilder().setUserId(user.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder()
                .userId(user.toString()).login("user").email("user@example.com").status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER).build());
    }
}
