package ru.taska.integration;

import java.util.UUID;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.VerifiedTenantContext;

final class TenantTestContext {

    private TenantTestContext() {
    }

    static <T> Mono<T> withTenant(Mono<T> operation) {
        var verified = verifiedTenant();
        return operation.contextWrite(context -> context.put(VerifiedTenantContext.class, verified));
    }

    static <T> Flux<T> withTenant(Flux<T> operation) {
        var verified = verifiedTenant();
        return operation.contextWrite(context -> context.put(VerifiedTenantContext.class, verified));
    }

    private static VerifiedTenantContext verifiedTenant() {
        var verified = Mockito.mock(VerifiedTenantContext.class);
        Mockito.when(verified.tenantId()).thenReturn(UUID.randomUUID().toString());
        return verified;
    }
}
