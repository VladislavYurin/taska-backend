package ru.taska.service.impl;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipPage;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.TenantMemberDto;
import ru.taska.exception.DomainStatus;
import ru.taska.service.grpc.client.TenantMembershipsClient;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.taska.util.DirectoryFixturesTest.TENANT_A;
import static ru.taska.util.DirectoryFixturesTest.TENANT_B;
import static ru.taska.util.DirectoryFixturesTest.assertDomain;
import static ru.taska.util.DirectoryFixturesTest.batch;
import static ru.taska.util.DirectoryFixturesTest.id;

/**
 * Проверяет объединение страницы membership с глобальными профилями.
 *
 * <p>Покрывает один batch на страницу, независимость от порядка ответа Auth,
 * сохранение cursor и membership-полей, одинаковые профили в разных компаниях,
 * отсутствие лишних вызовов и закрытый отказ при несогласованных ответах.
 */
@Timeout(10)
@DisplayName("TenantMembersServiceImpl.list: композиция разрешённой страницы с глобальными профилями")
class TenantMembersServiceImplTest {

    private TenantMembershipsClient members;
    private GrpcUserProfileServiceClient profiles;
    private TenantMembersServiceImpl service;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        members = mock(TenantMembershipsClient.class);
        profiles = mock(GrpcUserProfileServiceClient.class);
        service = new TenantMembersServiceImpl(members, profiles);
        context = mock(GatewayContext.class);
    }

    private Membership member(UUID tenant, long number) {
        return Membership.newBuilder()
                .setId(id(number + 1000).toString())
                .setTenantId(tenant.toString())
                .setUserId(id(number).toString())
                .setRole(Membership.RoleValue.ROLE_MEMBER)
                .setState(Membership.StateValue.STATE_ACTIVE)
                .setVersion("7")
                .build();
    }

    private UserDetails profile(long number) {
        return UserDetails.newBuilder()
                .setUserId(id(number).toString())
                .setDisplayName("Global " + number)
                .setEmail("user" + number + "@example.test")
                .build();
    }

    private void page(MembershipPage page) {
        when(members.list(TENANT_A, null, 50, context))
                .thenReturn(Mono.just(page));
    }

    private void profileResponse(UserDetails... items) {
        when(profiles.getUserDetailsByIds(anyList(), eq(context)))
                .thenReturn(Mono.just(
                        GetUserDetailsByIdsResponse.newBuilder()
                                .addAllUserDetails(List.of(items))
                                .build()
                ));
    }

    /**
     * Проверяет соединение по userId, а не по позиции в ответе Auth.
     * Порядок membership, cursor, роль, состояние и owner-флаг должны сохраняться.
     */
    @Test
    @DisplayName("Страница обогащается одним batch с сохранением порядка, cursor и membership-полей")
    void enrichesPageInMembershipOrder() {
        var first = member(TENANT_A, 10).toBuilder()
                .setRole(Membership.RoleValue.ROLE_TENANT_ADMIN)
                .setIsOwner(true)
                .build();
        var second = member(TENANT_A, 11).toBuilder()
                .setState(Membership.StateValue.STATE_SUSPENDED)
                .build();

        page(MembershipPage.newBuilder()
                .addItems(first)
                .addItems(second)
                .setNextCursor("next-cursor")
                .build());

        var firstProfile = profile(10).toBuilder()
                .setAvatar(AvatarResponse.newBuilder()
                        .setObjectKey("internal/object-key")
                        .setDownloadUrl("https://storage.example.test/signed"))
                .build();

        profileResponse(profile(11), firstProfile);

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .assertNext(result -> {
                    assertEquals("next-cursor", result.getNextCursor());
                    assertEquals(
                            List.of(id(10), id(11)),
                            result.getItems().stream()
                                    .map(TenantMemberDto::getUserId)
                                    .toList()
                    );

                    var item = result.getItems().getFirst();
                    assertEquals(UUID.fromString(first.getId()), item.getId());
                    assertEquals(TENANT_A, item.getTenantId());
                    assertEquals(TenantMemberDto.RoleEnum.TENANT_ADMIN, item.getRole());
                    assertTrue(item.getIsOwner());
                    assertEquals("7", item.getVersion());
                    assertEquals("Global 10", item.getDisplayName());
                    assertEquals("user10@example.test", item.getEmail());
                    assertEquals(
                            "https://storage.example.test/signed",
                            item.getAvatarPath()
                    );
                    assertNotEquals("internal/object-key", item.getAvatarPath());
                    assertEquals(
                            TenantMemberDto.StateEnum.SUSPENDED,
                            result.getItems().get(1).getState()
                    );
                })
                .verifyComplete();

        verify(profiles, times(1)).getUserDetailsByIds(
                List.of(id(10), id(11)), context
        );
    }

    /**
     * Проверяет окончание списка без nextCursor и отсутствие avatarPath,
     * когда профиль не содержит аватар. Пустая строка не подменяет отсутствие cursor.
     */
    @Test
    @DisplayName("Последняя страница без аватаров возвращается без nextCursor и avatarPath")
    void preservesAbsentOptionalFields() {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10))
                .build());
        profileResponse(profile(10));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .assertNext(result -> {
                    assertNull(result.getNextCursor());
                    assertNull(result.getItems().getFirst().getAvatarPath());
                })
                .verifyComplete();
    }

    /**
     * Проверяет обработку пустой страницы tenant-service.
     * При отсутствии userId дополнительный RPC за профилями не требуется.
     */
    @Test
    @DisplayName("Пустая страница возвращается без запроса профилей")
    void avoidsProfileCallForEmptyPage() {
        page(MembershipPage.getDefaultInstance());
        // Стаб нужен, чтобы текущая реализация дошла до проверки лишнего вызова.
        profileResponse();

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .assertNext(result -> {
                    assertTrue(result.getItems().isEmpty());
                    assertNull(result.getNextCursor());
                })
                .verifyComplete();

        verifyNoInteractions(profiles);
    }

    /**
     * Проверяет, что отказ tenant-service, включая отказ доступа или отозванный cursor,
     * не приводит к вызову Auth и попытке получить глобальные профили.
     */
    @Test
    @DisplayName("Отказ получения membership-страницы прекращает композицию до вызова Auth")
    void propagatesMembershipFailure() {
        var failure = Status.NOT_FOUND.asRuntimeException();
        when(members.list(TENANT_A, null, 50, context))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        verifyNoInteractions(profiles);
    }

    /**
     * Проверяет отказ при пустом Mono от tenant-service.
     * Отсутствие ответа нельзя считать корректной пустой страницей.
     */
    @Test
    @DisplayName("Отсутствующий ответ tenant-service превращается в UNAVAILABLE")
    void rejectsEmptyMembershipResponse() {
        when(members.list(TENANT_A, null, 50, context))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(
                        error -> assertDomain(error, DomainStatus.UNAVAILABLE)
                )
                .verify();

        verifyNoInteractions(profiles);
    }

    /**
     * Проверяет некорректный состав batch профилей: повторный UUID либо UUID,
     * которого вообще нет в запрошенной membership-странице.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Дублированный или посторонний профиль в ответе Auth отклоняется")
    void rejectsUnexpectedProfiles(boolean duplicate) {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10))
                .build());

        profileResponse(
                profile(10),
                duplicate ? profile(10) : profile(99)
        );

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(
                        error -> assertDomain(error, DomainStatus.UNAVAILABLE)
                )
                .verify();
    }

    /**
     * Проверяет отсутствие обязательного профиля для существующего membership.
     * Адаптер должен вернуть управляемую ошибку зависимости, а не NPE или неполную страницу.
     */
    @Test
    @DisplayName("Недостающий профиль участника возвращает UNAVAILABLE вместо NullPointerException")
    void rejectsMissingProfile() {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10))
                .addItems(member(TENANT_A, 11))
                .build());
        profileResponse(profile(10));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(
                        error -> assertDomain(error, DomainStatus.UNAVAILABLE)
                )
                .verify();
    }

    /**
     * Проверяет закрытый отказ при ошибке либо отсутствии ответа Auth.
     * Membership без глобальных профилей не должен выдаваться как полный directory.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Ошибка или отсутствие ответа Auth не превращается в успешную страницу")
    void propagatesProfileFailure(boolean empty) {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10))
                .build());

        var failure = Status.UNAVAILABLE.asRuntimeException();
        when(profiles.getUserDetailsByIds(anyList(), eq(context)))
                .thenReturn(empty ? Mono.empty() : Mono.error(failure));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(error -> {
                    if (empty) {
                        assertDomain(error, DomainStatus.UNAVAILABLE);
                    } else {
                        assertSame(failure, error);
                    }
                })
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1"})
    @DisplayName("Некорректный UUID membership даёт UNAVAILABLE без частичной страницы")
    void rejectsInvalidMembershipId(String membershipId) {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10))
                .addItems(member(TENANT_A, 11).toBuilder().setId(membershipId))
                .build());
        profileResponse(profile(10), profile(11));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(error -> assertDomain(error, DomainStatus.UNAVAILABLE))
                .verify();
    }

    /**
     * Проверяет глобальность профиля аккаунта, состоящего одновременно в A и B.
     * Tenant-поля различаются, но имя, email и изображение остаются одними и теми же.
     */
    @Test
    @DisplayName("Один userId в двух компаниях получает одинаковый глобальный профиль")
    void usesSameGlobalProfileAcrossCompanies() {
        when(members.list(TENANT_A, null, 50, context))
                .thenReturn(Mono.just(MembershipPage.newBuilder()
                        .addItems(member(TENANT_A, 10)).build()));
        when(members.list(TENANT_B, null, 50, context))
                .thenReturn(Mono.just(MembershipPage.newBuilder()
                        .addItems(member(TENANT_B, 10)).build()));

        profileResponse(profile(10).toBuilder()
                .setAvatar(AvatarResponse.newBuilder()
                        .setDownloadUrl("https://storage.example.test/shared"))
                .build());

        StepVerifier.create(Mono.zip(
                        service.list(TENANT_A, null, 50, context),
                        service.list(TENANT_B, null, 50, context)
                ))
                .assertNext(pair -> {
                    var a = pair.getT1().getItems().getFirst();
                    var b = pair.getT2().getItems().getFirst();

                    assertEquals(TENANT_A, a.getTenantId());
                    assertEquals(TENANT_B, b.getTenantId());
                    assertEquals(a.getUserId(), b.getUserId());
                    assertEquals(a.getDisplayName(), b.getDisplayName());
                    assertEquals(a.getEmail(), b.getEmail());
                    assertEquals(a.getAvatarPath(), b.getAvatarPath());
                })
                .verifyComplete();
    }

    /**
     * Проверяет отказ для недопустимого состояния membership в публичном списке.
     * REMOVED и неизвестные значения не должны превращаться в видимого участника.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 3, 999})
    @DisplayName("Неизвестное или REMOVED состояние membership не попадает в публичную страницу")
    void rejectsInvalidMembershipState(int state) {
        page(MembershipPage.newBuilder()
                .addItems(member(TENANT_A, 10).toBuilder().setStateValue(state))
                .build());
        profileResponse(profile(10));

        StepVerifier.create(service.list(TENANT_A, null, 50, context))
                .expectErrorSatisfies(
                        error -> assertDomain(error, DomainStatus.UNAVAILABLE)
                )
                .verify();
    }
}