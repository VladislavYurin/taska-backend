package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.issue.attachment.v1.*;
import ru.taska.quota.SignedQuotaClient;

class StorageRecoveryDelegationTest {
  @TempDir Path directory;

  @Test
  void cleanupIdentitySurvivesQuotaAndPrimaryAdmissionDelegation() throws Exception {
    String self = AttachmentAdmissionServiceGrpc.getReconcileDeletionsMethod().getFullMethodName();
    String lookup = QuotaAuthorityServiceGrpc.getGetQuotaReservationMethod().getFullMethodName();
    String admission = AttachmentAdmissionServiceGrpc.getCheckAdmissionMethod().getFullMethodName();
    var f = new ManagedTrustFixture(directory, List.of("issue-service", "tenant-service"));
    add(f, "issue-service", "issue-service", self, "reconcileAttachmentDeletions");
    add(f, "issue-service", "tenant-service", lookup, "getQuotaReservation");
    add(f, "tenant-service", "issue-service", admission, "checkAttachmentAdmission");
    f.write();
    var issue = f.trust("issue-service");
    var root = f.trust("tenant-service");
    var issueSecurity = security("issue-service", issue);
    var rootSecurity = security("tenant-service", root);
    var contexts = new ManagedServiceContexts("issue-service", issue, issueSecurity);
    UUID tenant = UUID.randomUUID(), op = UUID.randomUUID();
    var policy = new RpcContextSecurity.Policy(true, false);
    var first =
        contexts
            .authorize(
                "STORAGE_CLEANUP",
                "issue-service",
                self,
                "reconcileAttachmentDeletions",
                tenant,
                Set.of("write", "storage:recover"),
                new RpcContextSecurity.Policy(true, true))
            .flatMap(
                c ->
                    RpcContextBridge.install(
                        new SignedQuotaClient(
                                issueSecurity, Clock.systemUTC(), "issue-service", "tenant-service")
                            .call(
                                tenant,
                                lookup,
                                "getQuotaReservation",
                                ctx ->
                                    GetQuotaReservationRequest.newBuilder()
                                        .setContext(ctx)
                                        .setOperationId(op.toString())
                                        .setKind(QuotaReservation.KindValue.KIND_STORAGE_BYTES)
                                        .build(),
                                (q, a) -> Mono.just(q)),
                        c))
            .block(Duration.ofSeconds(10));
    assertNotNull(first);
    var second =
        rootSecurity
            .authorize(first.getContext(), lookup, policy)
            .flatMap(
                c ->
                    RpcContextBridge.install(
                        new SignedQuotaClient(
                                rootSecurity, Clock.systemUTC(), "tenant-service", "issue-service")
                            .call(
                                tenant,
                                admission,
                                "checkAttachmentAdmission",
                                ctx ->
                                    AttachmentAdmissionRequest.newBuilder()
                                        .setContext(ctx)
                                        .setOperationId(op.toString())
                                        .setResourceId(UUID.randomUUID().toString())
                                        .setProjectId(UUID.randomUUID().toString())
                                        .setIssueId(UUID.randomUUID().toString())
                                        .setBytes(10)
                                        .setExpiresAt(Instant.now().plusSeconds(900).toString())
                                        .setPhase("LOOKUP")
                                        .build(),
                                (q, a) -> Mono.just(q)),
                        c))
            .block(Duration.ofSeconds(10));
    assertEquals("issue-service", second.getContext().getContext().getServiceIdentity());
    assertFalse(second.getContext().getContext().hasActorUserId());
  }

  private void add(
      ManagedTrustFixture f, String issuer, String audience, String rpc, String action) {
    f.edges.add(
        new ManagedRpcTrust.Edge(
            issuer,
            audience,
            rpc,
            action,
            Set.of("SERVICE"),
            Set.of("write", "storage:recover"),
            "STORAGE_CLEANUP",
            Set.of("issue-service"),
            "BOUND",
            Set.of()));
  }

  private RpcContextSecurity security(String service, ManagedRpcTrust trust) {
    return new RpcContextSecurity(
        service,
        Clock.systemUTC(),
        trust,
        (i, j, t) -> Mono.just(true),
        (c, r, p) -> Mono.just(trust.allowed(c, r, p)));
  }
}
