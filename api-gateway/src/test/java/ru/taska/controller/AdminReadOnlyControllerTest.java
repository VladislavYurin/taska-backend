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
import ru.taska.domain.dto.ReadOnlyResponseDto;
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
        mockAuthenticatedUser();
    }

    // ==================== ТЕСТЫ getMetadata ====================

    @Test
    @DisplayName("Должен вернуть ответ с телом MetadataResponse и статусом 200")
    void getMetadata_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new MetadataResponse();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/readonly/metadata")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(MetadataResponse.class).isEqualTo(response);

        Mockito.verify(adminClient).getCatalog(Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 503 Unavailable если downstream недоступен")
    void getMetadata_shouldThrowsExceptionAndStatus503_whenDownstreamUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.getCatalog(Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/readonly/metadata")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 401 Unauthorized если нет токена")
    void getMetadata_shouldThrowsExceptionAndStatus401_whenJwtTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/readonly/metadata")
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

    // ==================== ТЕСТЫ listTableRows ====================

    @Test
    @DisplayName("Должен вернуть ответ с телом ReadOnlyResponseDto и статусом 200")
    void listTableRows_shouldReturnsResponseAndStatus200() {
        var response = new ReadOnlyResponseDto();

        Map<String, String> filters = new HashMap<>();
        filters.put("status", "active");

        Mockito.when(adminClient.listTableRows(
                        Mockito.eq(SERVICE_KEY),
                        Mockito.eq(TABLE_NAME),
                        Mockito.eq(1),
                        Mockito.eq(20),
                        Mockito.eq("created_at"),
                        Mockito.eq("desc"),
                        Mockito.eq(filters),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder
                        .path("/api/v1/readonly/{service}/{table}")
                        .queryParam("page", 1)
                        .queryParam("pageSize", 20)
                        .queryParam("sort", "created_at")
                        .queryParam("order", "desc")
                        .queryParam("status", "active")
                        .build(SERVICE_KEY, TABLE_NAME))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ReadOnlyResponseDto.class).isEqualTo(response);

        Mockito.verify(adminClient).listTableRows(
                Mockito.eq(SERVICE_KEY),
                Mockito.eq(TABLE_NAME),
                Mockito.eq(1),
                Mockito.eq(20),
                Mockito.eq("created_at"),
                Mockito.eq("desc"),
                Mockito.eq(filters),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен вернуть ответ с фильтром contains")
    void listTableRows_shouldHandleContainsFilter() {
        var response = new ReadOnlyResponseDto();

        Map<String, Map<String, String>> filters = new HashMap<>();
        Map<String, String> operators = new HashMap<>();
        operators.put("contains", "@test.com");
        filters.put("email", operators);

        Mockito.when(adminClient.listTableRows(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.anyMap(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder
                        .path("/api/v1/readonly/{service}/{table}")
                        .queryParam("filters[email][contains]", "@test.com")
                        .build(SERVICE_KEY, TABLE_NAME))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть ответ с диапазоном дат from и to")
    void listTableRows_shouldHandleDateRangeFilters() {

        var response = new ReadOnlyResponseDto();

        Map<String, String> filters = new HashMap<>();
        filters.put("created_at.from", "2026-01-01T00:00:00Z");
        filters.put("created_at.to", "2026-12-31T23:59:59Z");

        Mockito.when(adminClient.listTableRows(
                        Mockito.eq(SERVICE_KEY),
                        Mockito.eq(TABLE_NAME),
                        Mockito.eq(1),
                        Mockito.eq(20),
                        Mockito.isNull(),
                        Mockito.eq("asc"),
                        Mockito.eq(filters),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder
                        .path("/api/v1/readonly/{service}/{table}")
                        .queryParam("created_at.from", "2026-01-01T00:00:00Z")
                        .queryParam("created_at.to", "2026-12-31T23:59:59Z")
                        .build(SERVICE_KEY, TABLE_NAME))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(adminClient).listTableRows(
                Mockito.eq(SERVICE_KEY),
                Mockito.eq(TABLE_NAME),
                Mockito.eq(1),
                Mockito.eq(20),
                Mockito.isNull(),
                Mockito.eq("asc"),
                Mockito.eq(filters),
                Mockito.any(GatewayContext.class)
        );
    }

    @ParameterizedTest
    @MethodSource("listTableRowsArguments")
    @DisplayName("Должен корректно обрабатывать все параметры")
    void listTableRows_shouldPassAllParameters(
            Consumer<UriBuilder> uriConfigurer,
            Integer expectedPage,
            Integer expectedPageSize,
            String expectedSort,
            String expectedOrder
    ) {
        mockAuthenticatedUser();

        var response = new ReadOnlyResponseDto();

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
                    builder.path("/api/v1/readonly/{service}/{table}");
                    uriConfigurer.accept(builder);
                    return builder.build(SERVICE_KEY, TABLE_NAME);
                })
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(adminClient).listTableRows(
                Mockito.eq(SERVICE_KEY),
                Mockito.eq(TABLE_NAME),
                Mockito.eq(expectedPage),
                Mockito.eq(expectedPageSize),
                Mockito.eq(expectedSort),
                Mockito.eq(expectedOrder),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 404 NotFound при ошибке в gRPC")
    void listTableRows_shouldThrowsExceptionAndStatus404_whenGrpcReturnsNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.listTableRows(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found")));

        webTestClient.get()
                .uri("/api/v1/readonly/{service}/{table}", SERVICE_KEY, TABLE_NAME)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(adminClient).listTableRows(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 403 Forbidden при недостатке прав")
    void listTableRows_shouldThrowsExceptionAndStatus403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.listTableRows(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied")));

        webTestClient.get()
                .uri("/api/v1/readonly/{service}/{table}", SERVICE_KEY, TABLE_NAME)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(adminClient).listTableRows(
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 401 Unauthorized если нет токена")
    void listTableRows_shouldThrowsExceptionAndStatus401_whenJwtTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/readonly/{service}/{table}", SERVICE_KEY, TABLE_NAME)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(adminClient, Mockito.never())
                .listTableRows(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any());
    }

    // ==================== HELPER METHODS ====================

    private static Stream<Arguments> listTableRowsArguments() {
        return Stream.of(
                Arguments.of(
                        "с параметрами по умолчанию",
                        (Consumer<UriBuilder>) builder -> {},
                        1,
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
                        1,
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