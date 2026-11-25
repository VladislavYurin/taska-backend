package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MembershipValidation;
import ru.taska.api.b2b.v1.MembershipValidationBatch;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.directory.v1.DirectoryUser;
import ru.taska.api.directory.v1.SearchUsersResponse;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.security.trust.GrpcTenantServiceClient;
import ru.taska.storage.client.StorageClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Проверяет критерии TAS-321 для поиска пользователей выбранной компании.
 *
 * <p>Тестовые данные содержат две компании, восемь глобальных аккаунтов
 * и девять membership. Алексей Иванов представлен одной identity,
 * которая состоит одновременно в обеих компаниях.
 *
 * <p>Проверяются:
 * <ul>
 *     <li>Изоляция выдачи A/B при глобальном поиске кандидатов;</li>
 *     <li>Отказ при подмене актора и отсутствии membership;</li>
 *     <li>Единое глобальное имя, email и аватар общей identity;</li>
 *     <li>Прекращение доступа после отзыва membership;</li>
 *     <li>Независимость страниц и hasMore от чужих пользователей.</li>
 * </ul>
 *
 * <p>Репозиторий и membership RPC заменены управляемыми тестовыми данными.
 * Тесты проверяют бизнес-логику search, но не реальный SQL, JWT или mTLS.
 */
@Timeout(10)
@DisplayName(
        "TAS-321 — search: изоляция компаний, глобальный профиль и отзыв доступа"
)
class UserDirectoryServiceTest {

    private static final UUID TENANT_A = uuid(1);
    private static final UUID TENANT_B = uuid(2);

    private static final UUID ACTOR_A = uuid(10);
    private static final UUID SMIRNOV = uuid(11);
    private static final UUID MARIA = uuid(12);

    private static final UUID ACTOR_B = uuid(20);
    private static final UUID KUZNETSOV = uuid(21);
    private static final UUID OLGA = uuid(22);
    private static final UUID SERGEY = uuid(23);

    private static final UUID SHARED_USER = uuid(30);

    private static final String SHARED_AVATAR_KEY =
            "avatars/alexey-ivanov.png";

    private static final String SHARED_AVATAR_URL =
            "https://storage.example.test/alexey-ivanov.png?signature=test";

    private UserRepository users;
    private GrpcTenantServiceClient tenantClient;
    private DirectoryAccess access;
    private DirectoryMembershipAccess membershipAccess;
    private StorageClient storage;
    private UserDirectoryService service;

    /**
     * Глобальный каталог аккаунтов.
     * Ключом является userId, а не пара tenantId/userId.
     */
    private Map<UUID, UserDetailsDto> globalProfiles;

    /**
     * Текущие активные membership.
     * Удаление UUID из набора моделирует отзыв участия в конкретной компании.
     */
    private Map<UUID, Set<UUID>> activeMemberships;

    private Map<UUID, User> accounts;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tenantClient = mock(GrpcTenantServiceClient.class);
        access = mock(DirectoryAccess.class);
        membershipAccess = mock(DirectoryMembershipAccess.class);
        storage = mock(StorageClient.class);

        var beans = new StaticListableBeanFactory();
        beans.addBean("grpcTenantServiceClient", tenantClient);

        service = new UserDirectoryService(
                users,
                beans.getBeanProvider(GrpcTenantServiceClient.class),
                access,
                storage,
                membershipAccess
        );

        globalProfiles = new LinkedHashMap<>();
        activeMemberships = new HashMap<>();
        accounts = new HashMap<>();

        addUser(
                ACTOR_A,
                "Иван Петров",
                "ivan.petrov@example.test"
        );
        addUser(
                SMIRNOV,
                "Иван Смирнов",
                "ivan.smirnov@example.test"
        );
        addUser(
                MARIA,
                "Мария Иванова",
                "maria.ivanova@example.test"
        );

        addUser(
                ACTOR_B,
                "Иван Сидоров",
                "ivan.sidorov@example.test"
        );
        addUser(
                KUZNETSOV,
                "Иван Кузнецов",
                "ivan.kuznetsov@example.test"
        );
        addUser(
                OLGA,
                "Ольга Иванова",
                "olga.ivanova@example.test"
        );
        addUser(
                SERGEY,
                "Сергей Иванов",
                "sergey.ivanov@example.test"
        );

        addUser(
                SHARED_USER,
                "Алексей Иванов",
                "alexey.ivanov@example.test"
        );

        globalProfiles.computeIfPresent(
                SHARED_USER,
                (k, sharedProfile) -> new UserDetailsDto(
                        sharedProfile.userId(),
                        sharedProfile.displayName(),
                        sharedProfile.email(),
                        AvatarDto.builder()
                                .id(uuid(300))
                                .userId(SHARED_USER)
                                .objectKey(SHARED_AVATAR_KEY)
                                .build()
                )
        );

        activeMemberships.put(
                TENANT_A,
                new HashSet<>(Set.of(
                        ACTOR_A,
                        SMIRNOV,
                        MARIA,
                        SHARED_USER
                ))
        );

        activeMemberships.put(
                TENANT_B,
                new HashSet<>(Set.of(
                        ACTOR_B,
                        KUZNETSOV,
                        OLGA,
                        SERGEY,
                        SHARED_USER
                ))
        );

        configureAccountAccess();
        configureMembershipAccess();
        configureGlobalSearch();
        configureMembershipBatch();

        when(storage.createPresignedDownloadUrl(SHARED_AVATAR_KEY))
                .thenReturn(Mono.just(SHARED_AVATAR_URL));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private void addUser(
            UUID userId,
            String displayName,
            String email
    ) {
        globalProfiles.put(
                userId,
                new UserDetailsDto(
                        userId,
                        displayName,
                        email,
                        null
                )
        );

        accounts.put(
                userId,
                User.builder()
                        .id(userId)
                        .displayName(displayName)
                        .email(email)
                        .status(UserStatus.ACTIVE)
                        .build()
        );
    }

    private void configureAccountAccess() {
        when(access.requireActor(any(UUID.class), eq(false)))
                .thenAnswer(invocation -> {
                    UUID actorId = invocation.getArgument(0);
                    User account = accounts.get(actorId);

                    if (account == null) {
                        return Mono.error(new DomainException(
                                DomainStatus.PERMISSION_DENIED,
                                "Account access denied"
                        ));
                    }

                    return Mono.just(account);
                });
    }

    private void configureMembershipAccess() {
        when(membershipAccess.check(any(User.class), anyString()))
                .thenAnswer(invocation -> {
                    User actor = invocation.getArgument(0);
                    UUID tenantId = UUID.fromString(
                            invocation.getArgument(1)
                    );

                    return Mono.defer(() -> {
                        boolean active = activeMemberships
                                .getOrDefault(tenantId, Set.of())
                                .contains(actor.getId());

                        if (!active) {
                            return Mono.error(new DomainException(
                                    DomainStatus.PERMISSION_DENIED,
                                    "Tenant membership is required",
                                    Map.of(
                                            "reason",
                                            "TENANT_MEMBERSHIP_REQUIRED"
                                    )
                            ));
                        }

                        return Mono.empty();
                    });
                });
    }

    private void configureGlobalSearch() {
        when(users.searchDirectory(
                anyString(),
                anyInt(),
                anyLong()
        )).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            long offset = invocation.getArgument(2);

            /*
             * В этих сценариях query содержит обычные имена без LIKE-метасимволов.
             * Mock моделирует глобальных кандидатов, а не реализацию PostgreSQL LIKE.
             */
            String query = pattern.substring(1, pattern.length() - 1);

            return Flux.defer(() -> {
                var candidates = globalProfiles.values().stream()
                        .filter(profile ->
                                profile.displayName()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query)
                                || profile.email()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query)
                        )
                        .sorted(
                                Comparator
                                        .comparing(
                                                (UserDetailsDto profile) ->
                                                        profile.displayName()
                                                                .toLowerCase(Locale.ROOT)
                                        )
                                        .thenComparing(
                                                profile -> profile.email()
                                                        .toLowerCase(Locale.ROOT)
                                        )
                                        .thenComparing(UserDetailsDto::userId)
                        )
                        .skip(offset)
                        .limit(limit)
                        .toList();

                return Flux.fromIterable(candidates);
            });
        });
    }

    private void configureMembershipBatch() {
        when(tenantClient.batchValidateMemberships(
                anyString(),
                anyList()
        )).thenAnswer(invocation -> {
            UUID tenantId = UUID.fromString(invocation.getArgument(0));
            List<UUID> requestedIds = invocation.getArgument(1);

            return Mono.fromSupplier(
                    () -> membershipBatch(tenantId, requestedIds)
            );
        });
    }

    private MembershipValidationBatch membershipBatch(
            UUID tenantId,
            List<UUID> requestedIds
    ) {
        Set<UUID> allowedIds = activeMemberships.getOrDefault(
                tenantId,
                Set.of()
        );

        var batch = MembershipValidationBatch.newBuilder()
                .setTenantId(tenantId.toString())
                .setTenantMembershipVersion("1")
                .setTenantLifecycle(
                        Tenant.LifecycleValue.LIFECYCLE_ACTIVE
                );

        for (UUID userId : requestedIds) {
            var item = MembershipValidation.newBuilder()
                    .setUserId(userId.toString());

            if (allowedIds.contains(userId)) {
                item.setFound(true);
                item.setMembership(
                        MembershipSnapshot.newBuilder()
                                .setTenantId(tenantId.toString())
                                .setUserId(userId.toString())
                                .setState(
                                        Membership.StateValue.STATE_ACTIVE
                                )
                                .setVersion("1")
                                .setTenantMembershipVersion("1")
                );
            }

            batch.addItems(item);
        }

        return batch.build();
    }

    private Context verifiedContext(
            UUID tenantId,
            UUID actorId
    ) {
        var verified = mock(VerifiedTenantContext.class);

        when(verified.tenantId()).thenReturn(tenantId.toString());
        when(verified.actorId()).thenReturn(actorId.toString());
        when(verified.scopes()).thenReturn(Set.of("read"));

        return Context.of(VerifiedTenantContext.class, verified);
    }

    private Mono<SearchUsersResponse> search(
            UUID tenantId,
            UUID actorId,
            String query,
            int page,
            int pageSize
    ) {
        return service.search(actorId, query, page, pageSize)
                .contextWrite(verifiedContext(tenantId, actorId));
    }

    private Mono<SearchUsersResponse> searchA(
            String query,
            int page,
            int pageSize
    ) {
        return search(TENANT_A, ACTOR_A, query, page, pageSize);
    }

    private Mono<SearchUsersResponse> searchB(
            String query,
            int page,
            int pageSize
    ) {
        return search(TENANT_B, ACTOR_B, query, page, pageSize);
    }

    private List<UUID> resultIds(SearchUsersResponse response) {
        return response.getItemsList().stream()
                .map(DirectoryUser::getId)
                .map(UUID::fromString)
                .toList();
    }

    private void assertDenied(Throwable error) {
        var exception = assertInstanceOf(DomainException.class, error);
        assertEquals(
                DomainStatus.PERMISSION_DENIED,
                exception.getStatus()
        );
    }

    /**
     * Проверяет первый критерий TAS-321: актор компании A получает только
     * участников A, несмотря на то что глобальный поиск возвращает также B.
     *
     * <p>Дополнительно проверяется, что внутренний membership batch действительно
     * содержит чужие UUID, но проверяется в области A и не выпускает их в ответ.
     */
    @Test
    @DisplayName(
            "Актор A видит только четырёх участников A, даже когда batch содержит UUID из B"
    )
    void actorASeesOnlyCompanyAMembers() {
        StepVerifier.create(searchA("Иван", 0, 50))
                .assertNext(response -> {
                    assertEquals(
                            List.of(
                                    SHARED_USER,
                                    ACTOR_A,
                                    SMIRNOV,
                                    MARIA
                            ),
                            resultIds(response)
                    );

                    assertEquals(4, response.getItemsCount());
                    assertFalse(response.getHasMore());

                    assertFalse(resultIds(response).contains(ACTOR_B));
                    assertFalse(resultIds(response).contains(KUZNETSOV));
                    assertFalse(resultIds(response).contains(OLGA));
                    assertFalse(resultIds(response).contains(SERGEY));
                })
                .verifyComplete();

        verify(tenantClient).batchValidateMemberships(
                eq(TENANT_A.toString()),
                argThat(ids ->
                        ids.size() == 8
                        && ids.contains(ACTOR_A)
                        && ids.contains(ACTOR_B)
                        && ids.contains(KUZNETSOV)
                        && ids.contains(SHARED_USER)
                )
        );

        verify(tenantClient, never()).batchValidateMemberships(
                eq(TENANT_B.toString()),
                anyList()
        );
    }

    /**
     * Проверяет симметричную изоляцию компаний: актор B получает участников B,
     * но не получает аккаунты, состоящие только в A.
     *
     * <p>Общий аккаунт возвращается, поскольку у него есть membership в B.
     */
    @Test
    @DisplayName(
            "Актор B видит пять участников B и не получает участников, состоящих только в A"
    )
    void actorBSeesOnlyCompanyBMembers() {
        StepVerifier.create(searchB("Иван", 0, 50))
                .assertNext(response -> {
                    assertEquals(
                            List.of(
                                    SHARED_USER,
                                    KUZNETSOV,
                                    ACTOR_B,
                                    OLGA,
                                    SERGEY
                            ),
                            resultIds(response)
                    );

                    assertEquals(5, response.getItemsCount());
                    assertFalse(response.getHasMore());

                    assertFalse(resultIds(response).contains(ACTOR_A));
                    assertFalse(resultIds(response).contains(SMIRNOV));
                    assertFalse(resultIds(response).contains(MARIA));
                })
                .verifyComplete();

        verify(tenantClient).batchValidateMemberships(
                eq(TENANT_B.toString()),
                anyList()
        );
    }

    static Stream<Arguments> nameQueries() {
        return Stream.of(
                Arguments.of(
                        "Иван",
                        List.of(SHARED_USER, ACTOR_A, SMIRNOV, MARIA)
                ),
                Arguments.of(
                        "Петров",
                        List.of(ACTOR_A)
                ),
                Arguments.of(
                        "Иван Смирнов",
                        List.of(SMIRNOV)
                ),
                Arguments.of(
                        "  ИВАН СМИРНОВ  ",
                        List.of(SMIRNOV)
                )
        );
    }

    /**
     * Проверяет реальные пользовательские запросы: имя, фамилию и полное имя.
     * Нормализация регистра и внешних пробелов не должна менять tenant-область.
     */
    @ParameterizedTest
    @MethodSource("nameQueries")
    @DisplayName(
            "Поиск по имени, фамилии и полному имени сохраняет ограничение выбранной компанией"
    )
    void searchesByRealNames(
            String query,
            List<UUID> expectedIds
    ) {
        StepVerifier.create(searchA(query, 0, 50))
                .assertNext(response -> {
                    assertEquals(expectedIds, resultIds(response));
                    assertEquals(
                            expectedIds.size(),
                            response.getItemsCount()
                    );
                    assertFalse(response.getHasMore());
                })
                .verifyComplete();
    }

    /**
     * Проверяет защиту от подмены actorId: в параметр search передаётся UUID
     * пользователя B, а проверенный контекст продолжает принадлежать актору A.
     *
     * <p>Отказ должен произойти до чтения аккаунтов, кандидатов и membership.
     */
    @Test
    @DisplayName(
            "Подмена actorId пользователя A на UUID пользователя B отклоняется до чтения данных"
    )
    void rejectsSpoofedActorUuid() {
        StepVerifier.create(
                        service.search(ACTOR_B, "Иван", 0, 50)
                                .contextWrite(
                                        verifiedContext(TENANT_A, ACTOR_A)
                                )
                )
                .expectErrorSatisfies(this::assertDenied)
                .verify();

        verifyNoInteractions(
                access,
                membershipAccess,
                users,
                tenantClient,
                storage
        );
    }

    /**
     * Проверяет дополнительную бизнес-проверку membership актора.
     * Если сервис получает область B для актора A, не состоящего в B,
     * выдача запрещается до глобального поиска.
     *
     * <p>Контекст создан тестом. Этот сценарий не утверждает возможность
     * подделать VerifiedTenantContext через публичный HTTP-запрос.
     */
    @Test
    @DisplayName(
            "Актор A без membership в B не получает directory B"
    )
    void rejectsCompanyWithoutActorMembership() {
        StepVerifier.create(
                        search(TENANT_B, ACTOR_A, "Иван", 0, 50)
                )
                .expectErrorSatisfies(this::assertDenied)
                .verify();

        verify(membershipAccess).check(
                accounts.get(ACTOR_A),
                TENANT_B.toString()
        );

        verifyNoInteractions(users, tenantClient, storage);
    }

    /**
     * Проверяет отсутствие утечки существования пользователя другой компании.
     * Поиск существующего только в B Кузнецова и поиск несуществующего имени
     * должны давать одинаковый публичный результат: пустые items и hasMore=false.
     */
    @Test
    @DisplayName(
            "Поиск чужой фамилии неотличим от поиска несуществующего пользователя"
    )
    void foreignUserLooksLikeMissingUser() {
        StepVerifier.create(
                        Mono.zip(
                                searchA("Кузнецов", 0, 10),
                                searchA("Несуществующий", 0, 10)
                        )
                )
                .assertNext(results -> {
                    var foreign = results.getT1();
                    var missing = results.getT2();

                    assertEquals(0, foreign.getItemsCount());
                    assertFalse(foreign.getHasMore());
                    assertEquals(missing, foreign);
                })
                .verifyComplete();

        verify(tenantClient).batchValidateMemberships(
                TENANT_A.toString(),
                List.of(KUZNETSOV)
        );

        verifyNoInteractions(storage);
    }

    /**
     * Проверяет второй критерий TAS-321: один userId в A/B получает данные
     * из одной глобальной записи профиля.
     *
     * <p>Сравниваются UUID, имя, email и URL одного тестового изображения.
     * В production разные presigned URL могут указывать на один и тот же аватар.
     */
    @Test
    @DisplayName(
            "Алексей Иванов в A и B имеет один UUID, глобальное имя, email и аватар"
    )
    void sharedAccountHasSameGlobalProfileInBothCompanies() {
        StepVerifier.create(
                        Mono.zip(
                                searchA("Алексей Иванов", 0, 10),
                                searchB("Алексей Иванов", 0, 10)
                        )
                )
                .assertNext(results -> {
                    var responseA = results.getT1();
                    var responseB = results.getT2();

                    assertEquals(1, responseA.getItemsCount());
                    assertEquals(1, responseB.getItemsCount());

                    var profileA = responseA.getItems(0);
                    var profileB = responseB.getItems(0);

                    assertEquals(
                            SHARED_USER.toString(),
                            profileA.getId()
                    );
                    assertEquals(profileA.getId(), profileB.getId());

                    assertEquals(
                            "Алексей Иванов",
                            profileA.getDisplayName()
                    );
                    assertEquals(
                            profileA.getDisplayName(),
                            profileB.getDisplayName()
                    );

                    assertEquals(
                            "alexey.ivanov@example.test",
                            profileA.getEmail()
                    );
                    assertEquals(
                            profileA.getEmail(),
                            profileB.getEmail()
                    );

                    assertEquals(
                            SHARED_AVATAR_URL,
                            profileA.getAvatarUrl()
                    );
                    assertEquals(
                            profileA.getAvatarUrl(),
                            profileB.getAvatarUrl()
                    );
                })
                .verifyComplete();

        verify(storage, times(2))
                .createPresignedDownloadUrl(SHARED_AVATAR_KEY);

        verify(tenantClient).batchValidateMemberships(
                TENANT_A.toString(),
                List.of(SHARED_USER)
        );

        verify(tenantClient).batchValidateMemberships(
                TENANT_B.toString(),
                List.of(SHARED_USER)
        );
    }

    /**
     * Проверяет третий критерий TAS-321: разрешение первого запроса
     * не должно сохраняться после отзыва membership актора.
     *
     * <p>Повторно используется тот же Mono и тот же проверенный контекст.
     * После отзыва повторная подписка обязана заново проверить membership
     * и завершиться до чтения кандидатов.
     */
    @Test
    @DisplayName(
            "Отзыв membership прекращает доступ при следующем запросе с прежним контекстом"
    )
    void membershipRevocationStopsFurtherSearches() {
        Mono<SearchUsersResponse> request = searchA("Иван", 0, 50);

        StepVerifier.create(request)
                .assertNext(response ->
                        assertEquals(4, response.getItemsCount())
                )
                .verifyComplete();

        activeMemberships.get(TENANT_A).remove(ACTOR_A);

        clearInvocations(
                users,
                tenantClient,
                storage,
                membershipAccess
        );

        StepVerifier.create(request)
                .expectErrorSatisfies(this::assertDenied)
                .verify();

        verify(membershipAccess).check(
                accounts.get(ACTOR_A),
                TENANT_A.toString()
        );

        verifyNoInteractions(users, tenantClient, storage);
    }

    /**
     * Проверяет, что membership привязано к конкретной компании.
     * Отзыв участия общей identity в A запрещает ей directory A,
     * но не отзывает независимо существующее membership в B.
     */
    @Test
    @DisplayName(
            "Отзыв membership общей identity в A не выдаёт доступ к A через её membership в B"
    )
    void membershipInOtherCompanyDoesNotRestoreRevokedAccess() {
        activeMemberships.get(TENANT_A).remove(SHARED_USER);

        StepVerifier.create(
                        search(TENANT_A, SHARED_USER, "Иван", 0, 50)
                )
                .expectErrorSatisfies(this::assertDenied)
                .verify();

        StepVerifier.create(
                        search(TENANT_B, SHARED_USER, "Иван", 0, 50)
                )
                .assertNext(response -> {
                    assertEquals(5, response.getItemsCount());
                    assertTrue(resultIds(response).contains(SHARED_USER));
                    assertFalse(resultIds(response).contains(ACTOR_A));
                })
                .verifyComplete();

        verify(tenantClient, never()).batchValidateMemberships(
                eq(TENANT_A.toString()),
                anyList()
        );
    }

    /**
     * Проверяет пользовательскую пагинацию после tenant-фильтрации.
     * Четыре участника A должны образовать две страницы по два элемента,
     * а третья страница должна быть пустой независимо от участников B.
     */
    @Test
    @DisplayName(
            "Страницы A содержат только участников A без повторов и чужого hasMore"
    )
    void paginationCountsOnlyCompanyAMembers() {
        StepVerifier.create(searchA("Иван", 0, 2))
                .assertNext(response -> {
                    assertEquals(
                            List.of(SHARED_USER, ACTOR_A),
                            resultIds(response)
                    );
                    assertEquals(2, response.getItemsCount());
                    assertTrue(response.getHasMore());
                })
                .verifyComplete();

        StepVerifier.create(searchA("Иван", 1, 2))
                .assertNext(response -> {
                    assertEquals(
                            List.of(SMIRNOV, MARIA),
                            resultIds(response)
                    );
                    assertEquals(2, response.getItemsCount());
                    assertFalse(response.getHasMore());
                })
                .verifyComplete();

        StepVerifier.create(searchA("Иван", 2, 2))
                .assertNext(response -> {
                    assertEquals(0, response.getItemsCount());
                    assertFalse(response.getHasMore());
                })
                .verifyComplete();
    }

    /**
     * Проверяет независимость публичного результата A от количества пользователей B.
     * После добавления 120 совпадающих по имени аккаунтов только в B
     * все страницы A, количество элементов и hasMore должны остаться прежними.
     *
     * <p>Количество кандидатов превышает внутренний batch size 100:
     * тест также проверяет переход к следующей порции поиска.
     */
    @Test
    @DisplayName(
            "Добавление 120 совпадающих пользователей B не меняет страницы, количество и hasMore компании A"
    )
    void foreignCompanyGrowthDoesNotChangePublicResults() {
        List<SearchUsersResponse> before = new ArrayList<>();

        StepVerifier.create(
                        Flux.range(0, 3)
                                .concatMap(page -> searchA("Иван", page, 2))
                )
                .recordWith(() -> before)
                .expectNextCount(3)
                .verifyComplete();

        for (int i = 0; i < 120; i++) {
            UUID userId = uuid(1000 + i);

            addUser(
                    userId,
                    "Иван Чужой " + i,
                    "foreign." + i + "@example.test"
            );

            activeMemberships.get(TENANT_B).add(userId);
        }

        List<SearchUsersResponse> after = new ArrayList<>();

        StepVerifier.create(
                        Flux.range(0, 3)
                                .concatMap(page -> searchA("Иван", page, 2))
                )
                .recordWith(() -> after)
                .expectNextCount(3)
                .verifyComplete();

        assertEquals(before, after);

        assertEquals(2, after.get(0).getItemsCount());
        assertTrue(after.get(0).getHasMore());

        assertEquals(2, after.get(1).getItemsCount());
        assertFalse(after.get(1).getHasMore());

        assertEquals(0, after.get(2).getItemsCount());
        assertFalse(after.get(2).getHasMore());

        verify(users, atLeastOnce()).searchDirectory(
                "%иван%",
                100,
                100L
        );
    }
}