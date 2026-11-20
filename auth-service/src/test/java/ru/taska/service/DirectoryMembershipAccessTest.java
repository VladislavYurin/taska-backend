package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.entity.User;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.trust.GrpcMembershipAuthority;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.taska.util.DirectoryFixturesTest.*;

/**
 * Проверяет допуск пользователя к справочнику на основании membership authority.
 *
 * <p>Покрывает успешное подтверждение, отсутствие участия, неактивное участие,
 * недоступность зависимости и повторную проверку после отзыва membership.
 * Реальный RPC и правила определения ACTIVE проверяются отдельно.
 */
@Timeout(10)
@DisplayName("DirectoryMembershipAccess: доступ только при подтверждённом текущем membership")
class DirectoryMembershipAccessTest {

    private GrpcMembershipAuthority authority;
    private DirectoryMembershipAccess service;
    private User actor;

    @BeforeEach
    void setUp() {
        authority = mock(GrpcMembershipAuthority.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("grpcMembershipAuthority", authority);
        service = new DirectoryMembershipAccess(beans.getBeanProvider(GrpcMembershipAuthority.class));
        actor = User.builder().id(ACTOR).build();
    }

    /**
     * Проверяет успешное завершение при получении поколения активного membership.
     * В authority должны передаваться UUID выбранной компании и самого актора.
     */
    @Test
    @DisplayName("Подтверждённое ACTIVE membership разрешает доступ к выбранной компании")
    void allowsConfirmedMembership() {
        when(authority.current(TENANT_A, ACTOR))
                .thenReturn(Mono.just(7L));

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .verifyComplete();

        verify(authority).current(TENANT_A, ACTOR);
        verifyNoMoreInteractions(authority);
    }

    /**
     * Проверяет отказ при отсутствии membership у актора.
     * Пустой ответ authority должен стать PERMISSION_DENIED с понятной причиной.
     */
    @Test
    @DisplayName("Отсутствующее membership возвращает TENANT_MEMBERSHIP_REQUIRED")
    void deniesMissingMembership() {
        when(authority.current(TENANT_A, ACTOR))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .expectErrorSatisfies(error -> {
                    assertDomain(error, DomainStatus.PERMISSION_DENIED);
                    assertEquals(
                            "TENANT_MEMBERSHIP_REQUIRED",
                            ((DomainException) error).getDetails().get("reason")
                    );
                })
                .verify();
    }

    /**
     * Проверяет сохранение отказа authority для неактивного membership.
     * Метод не должен превращать такую ошибку в успешный Mono<Void>.
     */
    @Test
    @DisplayName("Неактивное membership не допускает пользователя к справочнику")
    void propagatesInactiveMembership() {
        var failure = new DomainException(
                DomainStatus.PERMISSION_DENIED,
                "MEMBERSHIP_INACTIVE"
        );
        when(authority.current(TENANT_A, ACTOR))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
    }

    /**
     * Проверяет закрытый отказ при недоступности сервиса membership.
     * Ошибка зависимости не должна трактоваться как наличие членства.
     */
    @Test
    @DisplayName("Недоступность authority прекращает проверку и не разрешает доступ")
    void propagatesAuthorityFailure() {
        var failure = new DomainException(
                DomainStatus.UNAVAILABLE,
                "Membership authority unavailable"
        );
        when(authority.current(TENANT_A, ACTOR))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
    }

    /**
     * Проверяет сценарий TAS-321: успешный первый запрос не сохраняет разрешение
     * для следующих запросов после отзыва membership.
     */
    @Test
    @DisplayName("Отзыв membership между запросами немедленно прекращает directory-доступ")
    void rechecksMembershipAfterRevocation() {
        when(authority.current(TENANT_A, ACTOR))
                .thenReturn(Mono.just(1L), Mono.empty());

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .verifyComplete();

        StepVerifier.create(service.check(actor, TENANT_A.toString()))
                .expectErrorSatisfies(
                        error -> assertDomain(error, DomainStatus.PERMISSION_DENIED)
                )
                .verify();

        verify(authority, times(2)).current(TENANT_A, ACTOR);
    }
}