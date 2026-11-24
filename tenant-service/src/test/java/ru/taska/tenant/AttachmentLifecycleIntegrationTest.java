package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.taska.tenant.StorageQuotaIntegrationTest.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.issue.attachment.v1.*;
import ru.taska.capability.Action;
import ru.taska.exception.*;
import ru.taska.issuestorage.*;
import ru.taska.security.tenant.*;
import ru.taska.storage.versioned.S3AttachmentContentObjects;

@Testcontainers
@Timeout(120)
class AttachmentLifecycleIntegrationTest {
  @Container static final PostgreSQLContainer<?> rootDatabase = rootPg;
  @Container static final PostgreSQLContainer<?> issueDatabase = ownerPg;
  @Container static final GenericContainer<?> objectContainer = s3container;
  @TempDir static Path material;
  final AttachmentUploadIntegrationTest upload = new AttachmentUploadIntegrationTest();
  AttachmentLifecycleStore store;
  AttachmentLifecyclePolicy policy;
  AttachmentLifecycle api;
  AttachmentDeletions worker;

  @BeforeAll
  static void setup() throws Exception {
    AttachmentUploadIntegrationTest.certs = material;
    AttachmentUploadIntegrationTest.setup();
  }

  @AfterAll
  static void stop() throws Exception {
    AttachmentUploadIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    upload.reset();
    store = new AttachmentLifecycleStore(ownerTx);
    var access = mock(TenantIssueAccess.class);
    when(access.authorize(any(), any(), any()))
        .thenAnswer(
            inv ->
                VerifiedTenantContext.current()
                    .flatMap(
                        c -> {
                          Action action = inv.getArgument(0);
                          UUID project = inv.getArgument(1);
                          if (!permitted.get()
                              || (action == Action.ATTACHMENT_DELETE && readOnly.get()))
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.PERMISSION_DENIED, "CURRENT_DENIAL"));
                          return Mono.just(
                              new TenantIssueAccess.Permit(
                                  UUID.fromString(c.tenantId()),
                                  UUID.fromString(c.actorId()),
                                  c.action(),
                                  project,
                                  Instant.now().plusSeconds(25),
                                  false,
                                  false,
                                  Set.of()));
                        }));
    policy =
        new AttachmentLifecyclePolicy(
            store,
            new TenantIssueStore(ownerTx, new tools.jackson.databind.ObjectMapper(), clock),
            access);
    api = new AttachmentLifecycle(store, policy, new S3AttachmentContentObjects(s3, BUCKET));
    worker = new AttachmentDeletions(store, owner, authorityClient);
  }

  <T> T user(UUID tenant, UUID actor, Mono<T> m) {
    return upload.as(tenant, actor, "attachmentLifecycle", m).block(WAIT);
  }

  <T> T service(Mono<T> m) {
    return service(m, "STORAGE_CLEANUP", true);
  }

  <T> T service(Mono<T> m, String purpose, boolean scope) {
    var c =
        TrustedTenantContext.newBuilder()
            .setTenantId(A.toString())
            .setIssuerService("issue-service")
            .setServiceIdentity("issue-service")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setServicePurpose(purpose)
            .setActorGeneration("1")
            .setAction("reconcileAttachmentDeletions")
            .addScopes("write");
    if (scope) c.addScopes("storage:recover");
    return RegistryTestContext.withClaims(c.build(), m).block(WAIT);
  }

  AttachmentResponse confirmed() throws Exception {
    var u = upload.begin(UUID.randomUUID().toString());
    upload.put(u);
    return upload
        .as(A, ACTOR, "confirmAttachmentUpload", upload.uploads.confirm(upload.confirmation(u)))
        .block(WAIT);
  }

  DeleteAttachmentRequestBody deletion(UUID actor, String id) {
    return DeleteAttachmentRequestBody.newBuilder()
        .setProjectId(PROJECT.toString())
        .setIssueId(ISSUE.toString())
        .setAttachmentId(id)
        .setActorUserId(actor.toString())
        .build();
  }

  ReadAttachmentContentRequest read(UUID actor, String id, boolean meta) {
    return ReadAttachmentContentRequest.newBuilder()
        .setActorUserId(actor.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(ISSUE.toString())
        .setAttachmentId(id)
        .setMetadataOnly(meta)
        .build();
  }

  void retryNow() {
    ownerAdmin
        .sql("UPDATE taska.attachment_deletion_jobs SET next_attempt_at=now(),lease_until=NULL")
        .then()
        .block(WAIT);
  }

  @Test
  void confirmedOnlyPinnedRangeAndForeignPathAreProtected() throws Exception {
    var file = confirmed();
    var metadata = user(A, ACTOR, api.content(read(ACTOR, file.getId(), true)));
    assertEquals(10, metadata.getTotalSize());
    var chunk =
        user(
            A,
            ACTOR,
            api.content(
                read(ACTOR, file.getId(), false).toBuilder()
                    .setOffset(2)
                    .setLength(4)
                    .setExpectedChecksum(file.getChecksum())
                    .build()));
    assertArrayEquals(new byte[4], chunk.getData().toByteArray());
    assertThrows(
        DomainException.class, () -> user(B, ACTOR, api.content(read(ACTOR, file.getId(), true))));
    assertThrows(
        DomainException.class,
        () ->
            user(
                A,
                ACTOR,
                api.content(
                    read(ACTOR, file.getId(), true).toBuilder()
                        .setProjectId(UUID.randomUUID().toString())
                        .build())));
    permitted.set(false);
    assertThrows(
        DomainException.class, () -> user(A, ACTOR, api.content(read(ACTOR, file.getId(), true))));
  }

  @Test
  void acceptedDeleteByAnotherMemberHidesImmediatelyAndSurvivesRestart() throws Exception {
    var file = confirmed();
    UUID member = UUID.randomUUID();
    user(A, member, api.delete(deletion(member, file.getId())));
    user(A, member, api.delete(deletion(member, file.getId())));
    assertEquals(1, rows(ownerAdmin, "attachment_deletion_jobs"));
    assertThrows(
        DomainException.class, () -> user(A, ACTOR, api.content(read(ACTOR, file.getId(), true))));
    assertEquals("10", usage(A).getUsed());
    var first = service(worker.reconcile(null, 100));
    assertEquals(1, first.failed());
    assertEquals("10", usage(A).getUsed());
    time.set(time.get().plusSeconds(301));
    objectOnline.set(false);
    retryNow();
    assertEquals(1, service(worker.reconcile(null, 1)).failed());
    assertEquals("10", usage(A).getUsed());
    objectOnline.set(true);
    retryNow();
    worker = new AttachmentDeletions(new AttachmentLifecycleStore(ownerTx), owner, authorityClient);
    assertEquals(1, service(worker.reconcile(null, 1)).completed());
    assertEquals("0", usage(A).getUsed());
    assertEquals(0, service(worker.reconcile(null, 100)).processed());
    var item = user(A, ACTOR, store.item(PROJECT, ISSUE, UUID.fromString(file.getId()), false));
    service(authorityClient.releaseUsage(item.reservation()));
    assertEquals("0", usage(A).getUsed());
    assertEquals(2, rows(rootAdmin, "quota_effects"));
    assertEquals(1, rows(ownerAdmin, "issue_attachments"));
    assertEquals("DELETED", item.state());
    assertFalse(
        s3.listObjectVersions(
                software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest.builder()
                    .bucket(BUCKET)
                    .prefix(item.key())
                    .build())
            .get()
            .hasVersions());
  }

  @Test
  void readOnlyDenialDoesNotCreateJobButUploadFlagOffAllowsRead() throws Exception {
    var file = confirmed();
    enabled.set(false);
    assertEquals(10, user(A, ACTOR, api.content(read(ACTOR, file.getId(), true))).getTotalSize());
    readOnly.set(true);
    assertThrows(
        DomainException.class, () -> user(A, ACTOR, api.delete(deletion(ACTOR, file.getId()))));
    assertEquals(0, rows(ownerAdmin, "attachment_deletion_jobs"));
    assertEquals("10", usage(A).getUsed());
  }

  @Test
  void staleLeaseCannotCompleteNewAttempt() throws Exception {
    var file = confirmed();
    user(A, ACTOR, api.delete(deletion(ACTOR, file.getId())));
    var first = service(store.claim(UUID.fromString(file.getId())));
    retryNow();
    var second = service(store.claim(UUID.fromString(file.getId())));
    assertTrue(second.epoch() > first.epoch());
    assertFalse(service(store.finish(first, null)));
    assertTrue(service(store.finish(second, "RETRY_REQUIRED")));
    assertEquals("10", usage(A).getUsed());
  }

  @Test
  void lostReleaseAcknowledgementRetriesOneLedgerEffectAfterRestart() throws Exception {
    var file = confirmed();
    user(A, ACTOR, api.delete(deletion(ACTOR, file.getId())));
    time.set(time.get().plusSeconds(301));
    loseUsageResponse.set(true);
    assertEquals(1, service(worker.reconcile(null, 1)).failed());
    assertEquals("0", usage(A).getUsed());
    assertEquals(2, rows(rootAdmin, "quota_effects"));
    loseUsageResponse.set(false);
    retryNow();
    worker = new AttachmentDeletions(new AttachmentLifecycleStore(ownerTx), owner, authorityClient);
    assertEquals(1, service(worker.reconcile(null, 1)).completed());
    assertEquals("0", usage(A).getUsed());
    assertEquals(2, rows(rootAdmin, "quota_effects"));
    assertEquals(
        1L,
        rootAdmin
            .sql("SELECT count(*) AS n FROM taska.quota_effects WHERE effect_kind='RELEASE_USAGE'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void repeatedDeleteDoesNotAdvanceManifestVersionOrResetRetry() throws Exception {
    var file = confirmed();
    user(A, ACTOR, api.delete(deletion(ACTOR, file.getId())));
    service(worker.reconcile(null, 1));
    String sql =
        "SELECT (SELECT version FROM taska.storage_manifest_versions WHERE tenant_id='"
            + A
            + "')::text||'/'||to_jsonb(d)::text AS snapshot FROM taska.attachment_deletion_jobs d";
    String before =
        ownerAdmin.sql(sql).map((r, m) -> r.get("snapshot", String.class)).one().block(WAIT);
    UUID member = UUID.randomUUID();
    reactor.core.publisher.Flux.merge(
            upload.as(A, ACTOR, "attachmentLifecycle", api.delete(deletion(ACTOR, file.getId()))),
            upload.as(A, member, "attachmentLifecycle", api.delete(deletion(member, file.getId()))))
        .then()
        .block(WAIT);
    assertEquals(
        before,
        ownerAdmin.sql(sql).map((r, m) -> r.get("snapshot", String.class)).one().block(WAIT));
  }

  @Test
  void emptyActualObjectCannotBeConfirmed() throws Exception {
    var u = upload.begin("empty-object");
    s3.putObject(
            b -> b.bucket(BUCKET).key(u.getObjectKey()).contentType("text/plain"),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(new byte[0]))
        .get();
    assertThrows(
        DomainException.class,
        () ->
            upload
                .as(
                    A,
                    ACTOR,
                    "confirmAttachmentUpload",
                    upload.uploads.confirm(upload.confirmation(u)))
                .block(WAIT));
    assertEquals(0, rows(ownerAdmin, "issue_attachments"));
    assertEquals("0", usage(A).getUsed());
  }

  @Test
  void serviceNeedsAcceptedDeletionAndExactPurposeAndScopes() throws Exception {
    var file = confirmed();
    var u =
        user(
            A,
            ACTOR,
            upload.store.operation(
                user(A, ACTOR, store.item(PROJECT, ISSUE, UUID.fromString(file.getId()), true))
                    .reservation()
                    .operation()));
    assertThrows(DomainException.class, () -> service(policy.bound(u, "DIRECT_DELETE")));
    assertThrows(DomainException.class, () -> service(policy.bound(u, "PROJECT_CLEANUP")));
    user(A, ACTOR, api.delete(deletion(ACTOR, file.getId())));
    assertThrows(DomainException.class, () -> service(policy.check(u), "PROJECT_CLEANUP", true));
    assertThrows(DomainException.class, () -> service(policy.check(u), "STORAGE_CLEANUP", false));
    permitted.set(false);
    assertNotNull(service(policy.check(u)));
  }

  @Test
  void tableRlsAndImmutableIdentityProtectOtherTenant() throws Exception {
    var file = confirmed();
    user(A, ACTOR, api.delete(deletion(ACTOR, file.getId())));
    assertFalse(
        user(
            B,
            ACTOR,
            store.hasDeletion(
                user(A, ACTOR, store.item(PROJECT, ISSUE, UUID.fromString(file.getId()), false))
                    .reservation()
                    .operation())));
    assertThrows(
        Exception.class,
        () ->
            user(
                A,
                ACTOR,
                ownerTx.execute(
                    db ->
                        db.sql("UPDATE taska.attachment_deletion_jobs SET initiator_id=:actor")
                            .bind("actor", UUID.randomUUID())
                            .fetch()
                            .rowsUpdated())));
    assertThrows(
        Exception.class,
        () ->
            user(
                A,
                ACTOR,
                ownerTx.execute(
                    db ->
                        db.sql("DELETE FROM taska.attachment_deletion_jobs")
                            .fetch()
                            .rowsUpdated())));
  }
}
