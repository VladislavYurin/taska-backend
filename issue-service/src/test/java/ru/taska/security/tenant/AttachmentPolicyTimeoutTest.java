package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.issuestorage.AttachmentUploadPolicy;
import ru.taska.issuestorage.PrimaryAttachmentPolicy;
import ru.taska.issuestorage.TenantIssueStore;

class AttachmentPolicyTimeoutTest {
  private final TenantDecisionFacts facts = mock(TenantDecisionFacts.class);
  private final PrimaryAttachmentPolicy policy =
      new PrimaryAttachmentPolicy(
          facts,
          mock(ManagedDomainReads.class),
          mock(RpcContextSecurity.class),
          mock(TenantIssueStore.class));

  private Mono<AttachmentUploadPolicy.Grant> check() {
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(UUID.randomUUID().toString())
            .setTenantId(UUID.randomUUID().toString())
            .addScopes("write")
            .build();
    var context =
        new VerifiedTenantContext(
            claims.getActorUserId(),
            claims.getTenantId(),
            UUID.randomUUID().toString(),
            "createAttachmentUploadUrl",
            java.util.Set.of("write"),
            ru.taska.api.b2b.v1.RequestContext.newBuilder()
                .setContext(claims)
                .build()
                .toByteArray());
    return policy
        .check(UUID.randomUUID(), UUID.randomUUID(), null, "MUTATION_CONTEXT")
        .contextWrite(c -> c.put(VerifiedTenantContext.class, context));
  }

  @Test
  void primarySourceDeadlineIsUnavailableBeforeIntentCreation() {
    StepVerifier.withVirtualTime(
            () -> {
              when(facts.currentAdministrative("write"))
                  .thenReturn(
                      Mono.<TenantDecisionFacts.Frame>never().timeout(Duration.ofSeconds(5)));
              return check();
            })
        .thenAwait(Duration.ofSeconds(5))
        .expectErrorSatisfies(
            error -> {
              var domain =
                  org.junit.jupiter.api.Assertions.assertInstanceOf(DomainException.class, error);
              assertEquals(DomainStatus.UNAVAILABLE, domain.getStatus());
              assertEquals("STORAGE_POLICY_UNAVAILABLE", domain.getMessage());
            })
        .verify();
  }

  @Test
  void businessAndTransportErrorsArePreserved() {
    for (var status :
        new DomainStatus[] {
          DomainStatus.PERMISSION_DENIED, DomainStatus.FAILED_PRECONDITION, DomainStatus.DATA_LOSS
        }) {
      var error = new DomainException(status, "OWNED_REFUSAL");
      when(facts.currentAdministrative("write")).thenReturn(Mono.error(error));
      StepVerifier.create(check())
          .expectErrorSatisfies(actual -> assertSame(error, actual))
          .verify();
    }
    var wire = io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException();
    when(facts.currentAdministrative("write")).thenReturn(Mono.error(wire));
    StepVerifier.create(check()).expectErrorSatisfies(actual -> assertSame(wire, actual)).verify();
  }
}
