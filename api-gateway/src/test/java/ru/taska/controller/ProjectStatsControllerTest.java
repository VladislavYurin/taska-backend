package ru.taska.controller;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import ru.taska.domain.dto.ListProjectStatsDto;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.service.impl.ProjectStatsService;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

@WebFluxTest(controllers = ProjectStatsController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class})
class ProjectStatsControllerTest {
    private static final String USER_ID = "d221b01d-9c5b-4c3b-b3be-b5502f9d1a12";
    @Autowired private WebTestClient client;
    @MockitoBean private ProjectStatsService service;
    @MockitoBean private GrpcAuthServiceClient authClient;
    @MockitoBean private ContextMapper contextMapper;

    @Test
    void authenticatedRequestReturnsEmptyArrayAndRequestId() {
        mockAuthenticatedUser();
        Mockito.when(service.getProjectStats(Mockito.any())).thenReturn(Mono.just(new ListProjectStatsDto(List.of())));
        client.get().uri("/api/v1/project-stats").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isOk().expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.items").isArray().jsonPath("$.items").isEmpty();
    }

    @Test
    void anonymousRequestReturns401WithoutReadingStats() {
        client.get().uri("/api/v1/project-stats").exchange().expectStatus().isUnauthorized();
        Mockito.verifyNoInteractions(service);
    }
    private void mockAuthenticatedUser() {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(
                        UserContext.newBuilder()
                                .setUserId(USER_ID)
                                .build()
                )
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .login("test-login")
                .email("test@example.com")
                .displayName("Test User")
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);

    }

}
