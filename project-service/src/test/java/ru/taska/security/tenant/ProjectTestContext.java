package ru.taska.security.tenant;

import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Test-only mint. Production transport must use the TAS-306 verifier, never raw tenant headers. */
public final class ProjectTestContext {
  public static <T> Mono<T> as(UUID tenant, UUID actor, Mono<T> work) {
    var context = new VerifiedTenantContext(actor==null?null:actor.toString(), tenant.toString(),
        UUID.randomUUID().toString(),"project.storage.test",Set.of("read","write"),ru.taska.api.b2b.v1.RequestContext.newBuilder().setContext(
            ru.taska.api.b2b.v1.TrustedTenantContext.newBuilder().setActorGeneration("3").setMembershipGeneration("5"))
            .build().toByteArray());
    return work.contextWrite(context::install);
  }
}
