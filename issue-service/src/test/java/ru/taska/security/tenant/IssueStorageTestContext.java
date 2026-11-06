package ru.taska.security.tenant;

import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;

/** Only the test artifact can mint this authority; production must verify TAS-306 assertions. */
public final class IssueStorageTestContext {
  public static <T> Mono<T> as(UUID tenant, UUID actor, Mono<T> work) {
    return as(tenant, actor, "createIssue", work);
  }

  public static <T> Mono<T> as(UUID tenant, UUID actor, String action, Mono<T> work) {
    var wire =
        RequestContext.newBuilder()
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setActorGeneration("3")
                    .setMembershipGeneration("5"))
            .build()
            .toByteArray();
    var context =
        new VerifiedTenantContext(
            actor == null ? null : actor.toString(),
            tenant.toString(),
            UUID.randomUUID().toString(),
            action,
            Set.of("read", "write"),
            wire);
    return work.contextWrite(context::install);
  }
}
