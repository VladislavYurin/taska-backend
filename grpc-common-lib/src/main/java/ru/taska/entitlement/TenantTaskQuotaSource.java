package ru.taska.entitlement;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.TenantTaskQuotaSummary;

@FunctionalInterface
public interface TenantTaskQuotaSource {
  Mono<TenantTaskQuotaSummary> current(UUID tenant);
}
