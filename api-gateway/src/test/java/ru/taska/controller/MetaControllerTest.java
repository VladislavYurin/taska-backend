package ru.taska.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.service.impl.MetaServiceImpl;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

@WebFluxTest(controllers = MetaController.class)
@Import({
        MetaServiceImpl.class,
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
@DisplayName("MetaController WebTestClient Tests")
class MetaControllerTest {

    private static final String TOKEN = "valid-access-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MetaServiceImpl metaService;

    @MockitoBean
    private GrpcAuthServiceClient grpcAuthServiceClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @MockitoSpyBean
    private GatewayContextFactory contextFactory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("GET /api/v1/meta - словари и ETag")
    void getMeta_success_returns200WithDictionariesAndEtag() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-meta")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-meta")
                .expectHeader().valueEquals(HttpHeaders.ETAG, metaService.getEtag())
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache, private")
                .expectBody()
                .jsonPath("$.issueTypes[0].code").isEqualTo("TASK")
                .jsonPath("$.issueTypes[0].name").isEqualTo("Задача")
                .jsonPath("$.priorities[2].code").isEqualTo("HIGH")
                .jsonPath("$.linkTypes[1].code").isEqualTo("RELATES_TO")
                .jsonPath("$.notificationTypes[0].code").isEqualTo("ISSUE_ASSIGNED")
                .jsonPath("$.notificationTypes[0].name").isEqualTo("Назначена задача")
                .jsonPath("$.userStatuses[3].code").isEqualTo("LOCKED")
                .jsonPath("$.userStatuses[3].name").isEqualTo("Залочен");
    }

    @Test
    @DisplayName("GET /api/v1/meta - совпавший If-None-Match возвращает 304 без тела")
    void getMeta_matchingIfNoneMatch_returns304() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpHeaders.IF_NONE_MATCH, metaService.getEtag())
                .exchange()
                .expectStatus().isNotModified()
                .expectHeader().valueEquals(HttpHeaders.ETAG, metaService.getEtag())
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/meta - устаревший If-None-Match возвращает 200 со словарями")
    void getMeta_staleIfNoneMatch_returns200() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpHeaders.IF_NONE_MATCH, "\"stale\"")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ETAG, metaService.getEtag())
                .expectBody()
                .jsonPath("$.issueTypes").isArray();
    }

    @Test
    @DisplayName("GET /api/v1/meta - без Bearer token возвращает 401")
    void getMeta_missingToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/meta")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    private void mockAuthenticatedUser() {
        ValidateAccessTokenResponse grpcResponse = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder()
                        .setUserId(USER_ID)
                        .build())
                .build();

        GatewayUserContext gatewayUserContext = new GatewayUserContext(
                USER_ID,
                "testuser",
                "test@example.com",
                "Test User",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER
        );

        Mockito.when(grpcAuthServiceClient.validateAccessToken(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(TOKEN)
                ))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(contextMapper.mapToGatewayUserContext(grpcResponse.getUserContext()))
                .thenReturn(gatewayUserContext);
    }
}
