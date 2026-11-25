package ru.taska.util;

import reactor.util.context.Context;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MembershipValidation;
import ru.taska.api.b2b.v1.MembershipValidationBatch;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.VerifiedTenantContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Создаёт синтетические данные для проверки directory без внешних сервисов.
 *
 * <p>Контекст является Mockito-моком и не подтверждает проверку JWT, mTLS,
 * подписи RPC или актуальности сессии. В production-код этот класс не входит.
 */
public final class DirectoryFixturesTest {

    public static final UUID TENANT_A = id(1);
    public static final UUID TENANT_B = id(2);
    public static final UUID ACTOR = id(3);

    private DirectoryFixturesTest() {
    }

    public static UUID id(long value) {
        return new UUID(0, value);
    }

    public static VerifiedTenantContext verified(
            UUID tenantId,
            Set<String> scopes
    ) {
        var context = mock(VerifiedTenantContext.class);
        when(context.tenantId()).thenReturn(tenantId.toString());
        when(context.actorId()).thenReturn(ACTOR.toString());
        when(context.scopes()).thenReturn(scopes);
        return context;
    }

    public static Context reactorContext(UUID tenantId) {
        return reactorContext(tenantId, Set.of("read"));
    }

    public static Context reactorContext(
            UUID tenantId,
            Set<String> scopes
    ) {
        return Context.of(
                VerifiedTenantContext.class,
                verified(tenantId, scopes)
        );
    }

    public static MembershipValidationBatch allowed(
            UUID tenantId,
            List<UUID> userIds
    ) {
        var states = userIds.stream().collect(Collectors.toMap(
                value -> value,
                value -> Membership.StateValue.STATE_ACTIVE
        ));
        return batch(tenantId, userIds, states);
    }

    public static MembershipValidationBatch batch(
            UUID tenantId,
            List<UUID> userIds,
            Map<UUID, Membership.StateValue> states
    ) {
        var result = MembershipValidationBatch.newBuilder()
                .setTenantId(tenantId.toString())
                .setTenantMembershipVersion("1")
                .setTenantLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE);

        for (UUID userId : userIds) {
            var item = MembershipValidation.newBuilder()
                    .setUserId(userId.toString());

            var state = states.get(userId);
            if (state != null) {
                item.setFound(true);
                item.setMembership(MembershipSnapshot.newBuilder()
                        .setTenantId(tenantId.toString())
                        .setUserId(userId.toString())
                        .setState(state)
                        .setVersion("1")
                        .setTenantMembershipVersion("1"));
            }

            result.addItems(item);
        }

        return result.build();
    }

    public static void assertDomain(
            Throwable error,
            DomainStatus expectedStatus
    ) {
        var exception = assertInstanceOf(DomainException.class, error);
        assertEquals(expectedStatus, exception.getStatus());
    }
}