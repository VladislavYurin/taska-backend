package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.issuebootstrap.AttachmentCleanupRecovery;
import ru.taska.issuestorage.*;
import ru.taska.security.tenant.*;

class AttachmentCleanupRecoveryTest {
  @Test
  void failingTenantDoesNotBlockOtherTenantAndEachJobUsesFreshAuthority() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), job = UUID.randomUUID();
    var store = mock(AttachmentLifecycleStore.class);
    var deletions = mock(AttachmentDeletions.class);
    var uploads = mock(AttachmentUploads.class);
    var contexts = mock(ManagedServiceContexts.class);
    var metrics = new SimpleMeterRegistry();
    var leases = new HashSet<String>();
    when(contexts.authorize(
            eq("STORAGE_CLEANUP"),
            eq("issue-service"),
            anyString(),
            anyString(),
            any(),
            eq(Set.of("write", "storage:recover")),
            any()))
        .thenAnswer(
            inv ->
                RegistryTestContext.withClaims(
                    TrustedTenantContext.newBuilder()
                        .setTenantId(inv.<UUID>getArgument(4).toString())
                        .setIssuerService("issue-service")
                        .setServiceIdentity("issue-service")
                        .setServicePurpose("STORAGE_CLEANUP")
                        .setAction(inv.getArgument(3))
                        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                        .addAllScopes(Set.of("write", "storage:recover"))
                        .build(),
                    VerifiedTenantContext.current()));
    when(store.pending())
        .thenReturn(
            VerifiedTenantContext.current()
                .flatMap(
                    c -> {
                      leases.add(c.requestId());
                      return c.tenantId().equals(a.toString())
                          ? Mono.error(new IllegalStateException("Owned authority offline"))
                          : Mono.just(1L);
                    }));
    when(store.due(isNull(), eq(100))).thenReturn(Mono.just(List.of(job)));
    AtomicInteger completed = new AtomicInteger();
    when(deletions.process(job))
        .thenReturn(
            VerifiedTenantContext.current()
                .map(
                    c -> {
                      assertEquals(b.toString(), c.tenantId());
                      assertTrue(leases.add(c.requestId()), "Job reused worklist lease");
                      completed.incrementAndGet();
                      return 0;
                    }));
    when(uploads.reconcile(isNull(), eq(1)))
        .thenReturn(
            VerifiedTenantContext.current()
                .map(
                    c -> {
                      assertEquals(b.toString(), c.tenantId());
                      assertEquals("reconcileAttachmentUploads", c.action());
                      return new AttachmentUploads.Recovery(1, 0, 0, "");
                    }));
    var scheduler =
        new AttachmentCleanupRecovery(store, deletions, uploads, contexts, metrics, a + "," + b);
    scheduler.tick();
    assertEquals(1, completed.get());
    assertEquals(1, metrics.get("taska.attachments.cleanup.scan_failures").counter().count());
    assertEquals(
        0, metrics.get("taska.attachments.cleanup.last_scan_epoch_seconds").gauge().value());
    scheduler.tick();
    assertEquals(2, completed.get(), "running flag was not reset after failure");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttachmentCleanupRecovery(
                store,
                deletions,
                uploads,
                contexts,
                metrics,
                java.util.stream.IntStream.range(0, 101)
                    .mapToObj(i -> UUID.randomUUID().toString())
                    .collect(java.util.stream.Collectors.joining(","))));
  }
}
