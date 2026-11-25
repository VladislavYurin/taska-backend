package ru.taska.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.MembershipPageDto;
import ru.taska.domain.dto.TenantMemberDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.GatewayValidationExceptionHandler;
import ru.taska.error.GatewayWebExceptionHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.service.TenantMembersService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Проверяет публичный HTTP adapter списка участников выбранной компании.
 *
 * <p>Покрывает:
 * <ul>
 *     <li>маршрут GET /api/v1/tenants/{tenantId}/members;</li>
 *     <li>передачу tenantId, cursor, limit и GatewayContext;</li>
 *     <li>значение limit по умолчанию и валидацию HTTP-параметров;</li>
 *     <li>сериализацию участников, cursor и подписанного URL аватара;</li>
 *     <li>отсутствие credentials и внутренних полей в JSON;</li>
 *     <li>преобразование ошибок в HTTP-статусы;</li>
 *     <li>запрет кеширования успешных ответов и ошибок после входа в контроллер.</li>
 * </ul>
 *
 * <p>GatewayRequestExecutor и обработчики HTTP-ошибок настоящие.
 * GatewayContextFactory и TenantMembersService заменены моками.
 * Тесты не подтверждают настоящую проверку JWT, membership, mTLS или RPC-подписи.
 */
@WebFluxTest(controllers = TenantMembersController.class)
@Import({
        GatewayRequestExecutor.class,
        RequestIdProvider.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class,
        GatewayWebExceptionHandler.class,
        GatewayValidationExceptionHandler.class
})
@Timeout(15)
@DisplayName(
        "TenantMembersController.listTenantMembers: HTTP-контракт, параметры и ошибки"
)
class TenantMembersControllerTest {

    private static final UUID COMPANY_A =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID COMPANY_B =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final UUID IVAN =
            UUID.fromString("00000000-0000-0000-0000-000000000010");

    private static final UUID MARIA =
            UUID.fromString("00000000-0000-0000-0000-000000000011");

    private static final UUID IVAN_MEMBERSHIP =
            UUID.fromString("00000000-0000-0000-0000-000000000110");

    private static final UUID MARIA_MEMBERSHIP =
            UUID.fromString("00000000-0000-0000-0000-000000000111");

    private static final String REQUEST_ID =
            "00000000-0000-0000-0000-000000000900";

    private static final String URL =
            "/api/v1/tenants/{tenantId}/members";

    private static final String AUTHORIZATION =
            "Bearer test-access-token";

    private static final String AVATAR_URL =
            "https://storage.example.test/ivan.png?signature=test";

    @Autowired
    private WebTestClient http;

    @Autowired
    private GatewayRequestExecutor executor;

    @MockitoBean
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private TenantMembersService members;

    private GatewayContext gatewayContext;

    @BeforeEach
    void setUp() {
        gatewayContext = new GatewayContext(
                REQUEST_ID,
                "api-gateway",
                null,
                null,
                null
        );

        when(contextFactory.buildContext(
                anyString(),
                any(),
                any(EndpointSecurity.class)
        )).thenReturn(Mono.just(gatewayContext));
    }

    private TenantMemberDto ivan(UUID company) {
        return new TenantMemberDto()
                .id(IVAN_MEMBERSHIP)
                .tenantId(company)
                .userId(IVAN)
                .role(TenantMemberDto.RoleEnum.TENANT_ADMIN)
                .state(TenantMemberDto.StateEnum.ACTIVE)
                .isOwner(true)
                .displayName("Иван Петров")
                .email("ivan.petrov@example.test")
                .avatarPath(AVATAR_URL)
                .version("7");
    }

    private TenantMemberDto maria(UUID company) {
        return new TenantMemberDto()
                .id(MARIA_MEMBERSHIP)
                .tenantId(company)
                .userId(MARIA)
                .role(TenantMemberDto.RoleEnum.MEMBER)
                .state(TenantMemberDto.StateEnum.SUSPENDED)
                .isOwner(false)
                .displayName("Мария Иванова")
                .email("maria.ivanova@example.test")
                .version("3");
    }

    private MembershipPageDto page(UUID company) {
        return new MembershipPageDto()
                .items(List.of(ivan(company), maria(company)))
                .nextCursor("next-cursor");
    }

    private MembershipPageDto emptyPage() {
        return new MembershipPageDto().items(List.of());
    }

    private void assertPrivateNoStore(String cacheControl) {
        assertNotNull(cacheControl);
        assertTrue(
                cacheControl.contains("private"),
                "Ответ должен быть приватным"
        );
        assertTrue(
                cacheControl.contains("no-store"),
                "Ответ не должен сохраняться в кеше"
        );
    }

    /**
     * Проверяет доступность маршрута, значение limit=50 по умолчанию
     * и использование защищённого режима GatewayRequestExecutor.
     *
     * <p>TenantId берётся из URL, cursor отсутствует, а GatewayContext
     * передаётся в сервис без замены другим объектом.
     */
    @Test
    @DisplayName(
            "GET без pagination передаёт tenantId, cursor=null и limit=50 через PROTECTED executor"
    )
    void usesDefaultPaginationAndProtectedExecutor() {
        when(members.list(
                COMPANY_A,
                null,
                50,
                gatewayContext
        )).thenReturn(Mono.just(page(COMPANY_A)));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .header("X-Request-Id", REQUEST_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", REQUEST_ID)
                .expectHeader().value(
                        HttpHeaders.CACHE_CONTROL,
                        this::assertPrivateNoStore
                )
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.nextCursor").isEqualTo("next-cursor");

        verify(contextFactory).buildContext(
                eq(REQUEST_ID),
                any(),
                eq(EndpointSecurity.PROTECTED)
        );

        verify(members).list(
                eq(COMPANY_A),
                isNull(),
                eq(50),
                same(gatewayContext)
        );
        verifyNoMoreInteractions(members);
    }

    /**
     * Проверяет передачу явных cursor и limit без декодирования
     * или самостоятельного изменения opaque cursor контроллером.
     *
     * <p>Граничные значения limit=1 и limit=100 должны допускаться контрактом.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    @DisplayName(
            "Граничные limit=1/100 и opaque cursor передаются в сервис без изменений"
    )
    void passesExplicitPagination(int limit) {
        String cursor = "opaque_cursor-123";

        when(members.list(
                COMPANY_A,
                cursor,
                limit,
                gatewayContext
        )).thenReturn(Mono.just(emptyPage()));

        http.get()
                .uri(builder -> builder
                        .path(URL)
                        .queryParam("cursor", cursor)
                        .queryParam("limit", limit)
                        .build(COMPANY_A))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk();

        verify(members).list(
                eq(COMPANY_A),
                eq(cursor),
                eq(limit),
                same(gatewayContext)
        );
    }

    /**
     * Проверяет, что контроллер передаёт UUID компании именно из URL.
     * Он не должен использовать сохранённый tenant предыдущего запроса
     * или подставлять одну фиксированную компанию.
     *
     * <p>Допустимость выбранной компании определяется зависимостями,
     * которые в данном тесте заменены моками.
     */
    @Test
    @DisplayName(
            "Разные tenantId из URL передаются в сервис как разные компании"
    )
    void forwardsRequestedTenantId() {
        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(Mono.just(emptyPage()));

        when(members.list(
                COMPANY_B, null, 50, gatewayContext
        )).thenReturn(Mono.just(emptyPage()));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk();

        http.get()
                .uri(URL, COMPANY_B)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk();

        verify(members).list(
                COMPANY_A, null, 50, gatewayContext
        );
        verify(members).list(
                COMPANY_B, null, 50, gatewayContext
        );
    }

    /**
     * Проверяет публичное представление участников: порядок, membership-поля,
     * глобальные имя/email и подписанный URL аватара.
     *
     * <p>JSON не должен содержать дополнительные поля credentials,
     * глобальную роль аккаунта или внутренний storage objectKey.
     */
    @Test
    @DisplayName(
            "JSON сохраняет порядок участников и содержит только публичные поля"
    )
    void serializesOnlyPublicMemberFields() {
        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(Mono.just(page(COMPANY_A)));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].id")
                .isEqualTo(IVAN_MEMBERSHIP.toString())
                .jsonPath("$.items[0].tenantId")
                .isEqualTo(COMPANY_A.toString())
                .jsonPath("$.items[0].userId")
                .isEqualTo(IVAN.toString())
                .jsonPath("$.items[0].role")
                .isEqualTo("TENANT_ADMIN")
                .jsonPath("$.items[0].state")
                .isEqualTo("ACTIVE")
                .jsonPath("$.items[0].isOwner")
                .isEqualTo(true)
                .jsonPath("$.items[0].displayName")
                .isEqualTo("Иван Петров")
                .jsonPath("$.items[0].email")
                .isEqualTo("ivan.petrov@example.test")
                .jsonPath("$.items[0].avatarPath")
                .isEqualTo(AVATAR_URL)
                .jsonPath("$.items[0].version")
                .isEqualTo("7")
                .jsonPath("$.items[1].userId")
                .isEqualTo(MARIA.toString())
                .jsonPath("$.items[1].state")
                .isEqualTo("SUSPENDED")
                .jsonPath("$.items[0]")
                .value(value -> {
                    var fields = assertInstanceOf(Map.class, value);

                    assertEquals(
                            Set.of(
                                    "id",
                                    "tenantId",
                                    "userId",
                                    "role",
                                    "state",
                                    "isOwner",
                                    "displayName",
                                    "email",
                                    "avatarPath",
                                    "version"
                            ),
                            fields.keySet()
                    );
                })
                .jsonPath("$.items[0].password").doesNotExist()
                .jsonPath("$.items[0].passwordHash").doesNotExist()
                .jsonPath("$.items[0].accessToken").doesNotExist()
                .jsonPath("$.items[0].refreshToken").doesNotExist()
                .jsonPath("$.items[0].globalRole").doesNotExist()
                .jsonPath("$.items[0].objectKey").doesNotExist();
    }

    /**
     * Проверяет корректный HTTP-ответ для пустой страницы.
     * Пустой список участников не должен превращаться в 404 или 204.
     */
    @Test
    @DisplayName(
            "Пустая страница возвращается как HTTP 200 с пустым items"
    )
    void returnsEmptyPage() {
        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(Mono.just(emptyPage()));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(
                        HttpHeaders.CACHE_CONTROL,
                        this::assertPrivateNoStore
                )
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.nextCursor").doesNotExist();
    }

    /**
     * Проверяет верхнюю допустимую длину cursor.
     * Контроллер должен передать все 2048 символов без обрезания значения.
     */
    @Test
    @DisplayName(
            "Cursor длиной 2048 символов принимается и передаётся целиком"
    )
    void acceptsMaximumCursorLength() {
        String cursor = "x".repeat(2048);

        when(members.list(
                COMPANY_A, cursor, 50, gatewayContext
        )).thenReturn(Mono.just(emptyPage()));

        http.get()
                .uri(builder -> builder
                        .path(URL)
                        .queryParam("cursor", cursor)
                        .build(COMPANY_A))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk();

        verify(members).list(
                COMPANY_A, cursor, 50, gatewayContext
        );
    }

    /**
     * Проверяет HTTP-валидацию limit, описанную в generated API.
     * Значения вне диапазона 1..100 и нечисловое значение
     * должны отклоняться до вызова бизнес-сервиса.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc"})
    @DisplayName(
            "Невалидный limit возвращает HTTP 400 без вызова TenantMembersService"
    )
    void rejectsInvalidLimit(String limit) {
        http.get()
                .uri(builder -> builder
                        .path(URL)
                        .queryParam("limit", limit)
                        .build(COMPANY_A))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(members);
    }

    /**
     * Проверяет ограничения длины cursor на HTTP-границе.
     * Пустой cursor и значение длиннее 2048 символов не должны доходить до сервиса.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 2049})
    @DisplayName(
            "Пустой или слишком длинный cursor возвращает HTTP 400 до вызова сервиса"
    )
    void rejectsInvalidCursorLength(int length) {
        String cursor = "x".repeat(length);

        http.get()
                .uri(builder -> builder
                        .path(URL)
                        .queryParam("cursor", cursor)
                        .build(COMPANY_A))
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(members);
    }

    /**
     * Проверяет преобразование UUID параметра пути.
     * Некорректная строка tenantId должна отклоняться до бизнес-операции.
     */
    @Test
    @DisplayName(
            "Некорректный UUID компании возвращает HTTP 400 без чтения участников"
    )
    void rejectsMalformedTenantId() {
        http.get()
                .uri(
                        "/api/v1/tenants/not-a-uuid/members"
                )
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(members);
    }

    static Stream<Arguments> contextFailures() {
        return Stream.of(
                Arguments.of(
                        DomainStatus.UNAUTHENTICATED,
                        401
                ),
                Arguments.of(
                        DomainStatus.PERMISSION_DENIED,
                        403
                )
        );
    }

    /**
     * Проверяет, что контроллер не продолжает работу после отказа фабрики контекста.
     * Ошибки аутентификации и разрешений должны возвращаться без вызова сервиса.
     *
     * <p>Тест моделирует результат проверки credential, а не проверяет настоящий токен.
     */
    @ParameterizedTest
    @MethodSource("contextFailures")
    @DisplayName(
            "Отказ GatewayContextFactory возвращается клиенту без чтения участников"
    )
    void stopsAfterContextFactoryFailure(
            DomainStatus status,
            int expectedHttpStatus
    ) {
        when(contextFactory.buildContext(
                anyString(),
                any(),
                eq(EndpointSecurity.PROTECTED)
        )).thenReturn(Mono.error(new DomainException(
                status,
                "Request access denied"
        )));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isEqualTo(expectedHttpStatus)
                .expectHeader().value(
                        HttpHeaders.CACHE_CONTROL,
                        this::assertPrivateNoStore
                )
                .expectBody()
                .jsonPath("$.items").doesNotExist();

        verifyNoInteractions(members);
    }

    static Stream<Arguments> serviceFailures() {
        return Stream.of(
                Arguments.of(
                        DomainStatus.PERMISSION_DENIED,
                        403
                ),
                Arguments.of(
                        DomainStatus.NOT_FOUND,
                        404
                ),
                Arguments.of(
                        DomainStatus.INVALID_ARGUMENT,
                        400
                ),
                Arguments.of(
                        DomainStatus.UNAVAILABLE,
                        503
                )
        );
    }

    /**
     * Проверяет преобразование доменных ошибок сервиса в HTTP.
     * Отзыв доступа, скрытый ресурс, недействительный cursor и сбой зависимости
     * не должны маскироваться успешной пустой страницей.
     */
    @ParameterizedTest
    @MethodSource("serviceFailures")
    @DisplayName(
            "Ошибки сервиса преобразуются в HTTP 403/404/400/503 без выдачи items"
    )
    void mapsServiceFailures(
            DomainStatus status,
            int expectedHttpStatus
    ) {
        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(Mono.error(new DomainException(
                status,
                "Directory request failed"
        )));

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isEqualTo(expectedHttpStatus)
                .expectHeader().value(
                        HttpHeaders.CACHE_CONTROL,
                        this::assertPrivateNoStore
                )
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.items").doesNotExist();
    }

    /**
     * Проверяет отсутствие кеширования успешного результата самим контроллером.
     * Если следующий вызов сервиса сообщает об отзыве доступа,
     * предыдущая успешная страница не должна возвращаться повторно.
     */
    @Test
    @DisplayName(
            "После успешной выдачи следующий отказ доступа возвращается как HTTP 403"
    )
    void doesNotReusePageAfterAccessRevocation() {
        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(
                Mono.just(page(COMPANY_A)),
                Mono.error(new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "Membership revoked"
                ))
        );

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2);

        http.get()
                .uri(URL, COMPANY_A)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().value(
                        HttpHeaders.CACHE_CONTROL,
                        this::assertPrivateNoStore
                )
                .expectBody()
                .jsonPath("$.items").doesNotExist();

        verify(members, times(2)).list(
                COMPANY_A, null, 50, gatewayContext
        );
    }

    /**
     * Проверяет защитную ветку limit==null непосредственно в Java-методе.
     * При HTTP-вызове Spring подставляет defaultValue=50 ещё до контроллера,
     * поэтому эту ветку нужно проверять отдельным прямым вызовом.
     */
    @Test
    @DisplayName(
            "Прямой вызов listTenantMembers с limit=null использует значение 50"
    )
    void directCallUsesDefaultLimitWhenNull() {
        var response = emptyPage();

        when(members.list(
                COMPANY_A, null, 50, gatewayContext
        )).thenReturn(Mono.just(response));

        var controller = new TenantMembersController(
                executor,
                members
        );

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/api/v1/tenants/" + COMPANY_A + "/members"
                )
        );

        StepVerifier.create(controller.listTenantMembers(
                        COMPANY_A,
                        null,
                        null,
                        exchange
                ))
                .assertNext(result -> {
                    assertEquals(HttpStatus.OK, result.getStatusCode());
                    assertSame(response, result.getBody());

                    assertPrivateNoStore(
                            result.getHeaders().getCacheControl()
                    );
                })
                .verifyComplete();

        assertPrivateNoStore(
                exchange.getResponse()
                        .getHeaders()
                        .getCacheControl()
        );

        verify(members).list(
                COMPANY_A, null, 50, gatewayContext
        );
    }
}