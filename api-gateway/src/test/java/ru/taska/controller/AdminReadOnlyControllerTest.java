package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAdminServiceClient;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

@WebFluxTest(controllers = AdminReadOnlyController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class AdminReadOnlyControllerTest {

    private static final String LOGIN = "admin";
    private static final String EMAIL = "admin@example.com";
    private static final String DISPLAY_NAME = "Admin Adminov";
    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SERVICE_KEY = "test-service";
    private static final String TABLE_NAME = "test_table";
    private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authServiceClient;

    @MockitoBean
    private GrpcAdminServiceClient adminClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ==================== ТЕСТЫ getCatalog ====================

    @Test
    @DisplayName("getCatalog: должен вернуть MetadataResponse и статус 200")
    void getCatalog_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new MetadataResponse();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/admin/catalog")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(MetadataResponse.class).isEqualTo(response);

        Mockito.verify(adminClient).getCatalog(Mockito.any(GatewayContext.class));
    }

    // ==================== ТЕСТЫ listTableRows ====================

    @Test
    @DisplayName("listTableRows: должен вернуть ReadOnlyTableRowsResponseDto и статус 200")
    void listTableRows_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminClient.listTableRows(
                        Mockito.eq(SERVICE_KEY),
                        Mockito.eq(TABLE_NAME),
                        Mockito.eq(1),
                        Mockito.eq(20),
                        Mockito.eq("created_at"),
                        Mockito.eq("desc"),
                        Mockito.anyMap(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder
                        .path("/api/v1/admin/{service}/{table}")
                        .queryParam("page", 1)
                        .queryParam("pageSize", 20)
                        .queryParam("sort", "created_at")
                        .queryParam("order", "desc")
                        .build(SERVICE_KEY, TABLE_NAME))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ReadOnlyTableRowsResponseDto.class).isEqualTo(response);
    }

    @ParameterizedTest
    @MethodSource("listTableRowsArguments")
    @DisplayName("listTableRows: должен корректно обрабатывать все параметры")
    void listTableRows_shouldPassAllParameters(
            String description,
            Consumer<UriBuilder> uriConfigurer,
            Integer expectedPage,
            Integer expectedPageSize,
            String expectedSort,
            String expectedOrder
    ) {
        mockAuthenticatedUser();

        var response = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminClient.listTableRows(
                        Mockito.eq(SERVICE_KEY),
                        Mockito.eq(TABLE_NAME),
                        Mockito.eq(expectedPage),
                        Mockito.eq(expectedPageSize),
                        Mockito.eq(expectedSort),
                        Mockito.eq(expectedOrder),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> {
                    builder.path("/api/v1/admin/{service}/{table}");
                    uriConfigurer.accept(builder);
                    return builder.build(SERVICE_KEY, TABLE_NAME);
                })
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("listTableRows: должен вернуть 404 при ошибке gRPC NOT_FOUND")
    void listTableRows_shouldReturn404_whenGrpcReturnsNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.listTableRows(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found")));

        webTestClient.get()
                .uri("/api/v1/admin/{service}/{table}", SERVICE_KEY, TABLE_NAME)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ==================== ТЕСТЫ getTableRowById ====================

    @Test
    @DisplayName("getTableRowById: должен вернуть ReadOnlySingleRowResponseDto и статус 200")
    void getTableRowById_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new ReadOnlySingleRowResponseDto();
        Map<String, Object> data = new HashMap<>();
        data.put("id", ROW_ID.toString());
        data.put("name", "test");
        response.setData(data);

        Mockito.when(adminClient.getTableRowById(
                        Mockito.eq(SERVICE_KEY),
                        Mockito.eq(TABLE_NAME),
                        Mockito.eq(ROW_ID),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/admin/{service}/{table}/{id}", SERVICE_KEY, TABLE_NAME, ROW_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ReadOnlySingleRowResponseDto.class).isEqualTo(response);

        Mockito.verify(adminClient).getTableRowById(
                Mockito.eq(SERVICE_KEY),
                Mockito.eq(TABLE_NAME),
                Mockito.eq(ROW_ID),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("getTableRowById: должен вернуть 404 если строка не найдена")
    void getTableRowById_shouldReturn404_whenRowNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.getTableRowById(
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Row not found")));

        webTestClient.get()
                .uri("/api/v1/admin/{service}/{table}/{id}", SERVICE_KEY, TABLE_NAME, ROW_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ==================== ОБЩИЕ ТЕСТЫ (authentication, downstream errors) ====================

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized без Bearer токена")
    void shouldReturn401_whenNoBearerToken() {
        webTestClient.get()
                .uri("/api/v1/admin/catalog")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(adminClient, Mockito.never())
                .getCatalog(Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при ошибке gRPC PERMISSION_DENIED")
    void shouldReturn403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.withDescription("Access Denied").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/admin/catalog")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 503 Service Unavailable при недоступном downstream")
    void shouldReturn503_whenDownstreamUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/admin/catalog")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout при превышении deadline")
    void shouldReturn504_whenDeadlineExceeded() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout Exceeded").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/admin/catalog")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== HELPER METHODS ====================

    private static Stream<Arguments> listTableRowsArguments() {
        return Stream.of(
                Arguments.of(
                        "с параметрами по умолчанию",
                        (Consumer<UriBuilder>) builder -> {},
                        0,
                        20,
                        null,
                        "asc"
                ),
                Arguments.of(
                        "с пагинацией",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("page", 2)
                                .queryParam("pageSize", 50),
                        2,
                        50,
                        null,
                        "asc"
                ),
                Arguments.of(
                        "с сортировкой",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("sort", "created_at")
                                .queryParam("order", "desc"),
                        0,
                        20,
                        "created_at",
                        "desc"
                ),
                Arguments.of(
                        "со всеми параметрами",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("page", 3)
                                .queryParam("pageSize", 100)
                                .queryParam("sort", "updated_at")
                                .queryParam("order", "asc"),
                        3,
                        100,
                        "updated_at",
                        "asc"
                )
        );
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
                .login(LOGIN)
                .email(EMAIL)
                .displayName(DISPLAY_NAME)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.GLOBAL_ADMIN)
                .build();

        Mockito.when(authServiceClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}
