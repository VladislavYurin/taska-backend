package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.taska.tenant.StorageQuotaIntegrationTest.A;
import static ru.taska.tenant.StorageQuotaIntegrationTest.ACTOR;
import static ru.taska.tenant.StorageQuotaIntegrationTest.B;
import static ru.taska.tenant.StorageQuotaIntegrationTest.BUCKET;
import static ru.taska.tenant.StorageQuotaIntegrationTest.ISSUE;
import static ru.taska.tenant.StorageQuotaIntegrationTest.PROJECT;
import static ru.taska.tenant.StorageQuotaIntegrationTest.WAIT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequestBody;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequestBody;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.issuestorage.AttachmentUploadPolicy;
import ru.taska.issuestorage.AttachmentUploadStore;
import ru.taska.issuestorage.AttachmentUploads;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.storage.versioned.S3UploadObjects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers
@Timeout(120)
class AttachmentUploadIntegrationTest {
  @Container static final PostgreSQLContainer<?> rootPg = StorageQuotaIntegrationTest.rootPg;
  @Container static final PostgreSQLContainer<?> ownerPg = StorageQuotaIntegrationTest.ownerPg;
  @Container static final GenericContainer<?> objects = StorageQuotaIntegrationTest.s3container;
  @TempDir static Path certs;
  static S3Presigner signer;
  final StorageQuotaIntegrationTest fixture = new StorageQuotaIntegrationTest();
  AttachmentUploadStore store;
  AttachmentUploads uploads;
  AttachmentUploadPolicy policy;

  @BeforeAll
  static void setup() throws Exception {
    StorageQuotaIntegrationTest.certs = certs;
    StorageQuotaIntegrationTest.setup();
    signer =
        S3Presigner.builder()
            .endpointOverride(
                URI.create("http://" + objects.getHost() + ":" + objects.getMappedPort(9000)))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("123", "123")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
  }

  @AfterAll
  static void close() throws Exception {
    if (signer != null) signer.close();
    StorageQuotaIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    fixture.seed();
    store =
        new AttachmentUploadStore(
            StorageQuotaIntegrationTest.ownerTx,
            StorageQuotaIntegrationTest.clock,
            java.util.Set.of("text/plain"));
    policy =
        (project, issue, u, phase) ->
            VerifiedTenantContext.current()
                .flatMap(
                    c -> {
                      boolean service =
                          RpcContextBridge.details(c).getContext().getCredentialKind()
                              == CredentialKind.CREDENTIAL_KIND_SERVICE;
                      if (!StorageQuotaIntegrationTest.permitted.get()
                          || !project.equals(PROJECT)
                          || !issue.equals(ISSUE)
                          || u != null && !service && !u.actor().toString().equals(c.actorId()))
                        return Mono.error(
                            new DomainException(
                                DomainStatus.PERMISSION_DENIED, "CURRENT_ACL_DENIED"));
                      if (!StorageQuotaIntegrationTest.enabled.get()
                          && List.of("PREPARE", "UPLOAD_WINDOW", "RESERVE").contains(phase)
                          && !StorageQuotaIntegrationTest.bypass.get())
                        return Mono.error(
                            new DomainException(
                                DomainStatus.FAILED_PRECONDITION, "CAPABILITY_DISABLED"));
                      return Mono.just(
                          new AttachmentUploadPolicy.Grant(
                              StorageQuotaIntegrationTest.bypass.get(),
                              20000000,
                              1,
                              Duration.ofMinutes(15)));
                    });
    uploads =
        new AttachmentUploads(
            store,
            StorageQuotaIntegrationTest.owner,
            StorageQuotaIntegrationTest.authorityClient,
            new S3UploadObjects(
                StorageQuotaIntegrationTest.s3,
                signer,
                StorageQuotaIntegrationTest.objectStore,
                BUCKET,
                StorageQuotaIntegrationTest.clock),
            policy,
            StorageQuotaIntegrationTest.clock);
  }

  <T> Mono<T> as(UUID tenant, UUID actor, String action, Mono<T> work) {
    return RegistryTestContext.withClaims(
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor.toString())
            .setTenantId(tenant.toString())
            .setAction(action)
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .setSessionId(UUID.randomUUID().toString())
            .setActorGeneration("3")
            .setMembershipGeneration("5")
            .addScopes("read")
            .addScopes("write")
            .build(),
        work);
  }

  CreateAttachmentUploadUrlRequestBody request(String key) {
    return CreateAttachmentUploadUrlRequestBody.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(ISSUE.toString())
        .setContentType("text/plain")
        .setFileName("report.txt")
        .setSizeBytes(10)
        .setIdempotencyKey(key)
        .build();
  }

  CreateAttachmentUploadUrlResponse begin(String key) {
    return as(A, ACTOR, "createAttachmentUploadUrl", uploads.initiate(request(key))).block(WAIT);
  }

  ConfirmAttachmentUploadRequestBody confirmation(CreateAttachmentUploadUrlResponse u) {
    return ConfirmAttachmentUploadRequestBody.newBuilder()
        .setObjectKey(u.getObjectKey())
        .setActorUserId(ACTOR.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(ISSUE.toString())
        .setFileName("report.txt")
        .setContentType("text/plain")
        .build();
  }

  void put(CreateAttachmentUploadUrlResponse u) throws Exception {
    var r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(u.getUploadUrl()))
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[10]))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
    assertTrue(r.statusCode() / 100 == 2, "Signed PUT must succeed");
  }

  long rows(String table) {
    return StorageQuotaIntegrationTest.rows(StorageQuotaIntegrationTest.ownerAdmin, table);
  }

  void sql(String s) {
    StorageQuotaIntegrationTest.ownerAdmin.sql(s).then().block(WAIT);
  }

  @Test
  void signedPutConfirmAndReplayCreateOneAttachmentAndOneUsageEffect() throws Exception {
    var u = begin("once");
    put(u);
    var q = confirmation(u);
    var first = as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(q)).block(WAIT);
    assertEquals(10, first.getSizeBytes());
    assertEquals(64, first.getChecksum().length());
    assertEquals(first, as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(q)).block(WAIT));
    assertEquals(1, rows("issue_attachments"));
    assertEquals(
        1,
        StorageQuotaIntegrationTest.rows(StorageQuotaIntegrationTest.rootAdmin, "quota_effects"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void repeatedInitiateUsesSameAdmissionAndRejectsChangedPayload() {
    var a = begin("key");
    var b = begin("key");
    assertEquals(a.getObjectKey(), b.getObjectKey());
    assertEquals(1, rows("attachment_uploads"));
    assertEquals(
        1,
        StorageQuotaIntegrationTest.rows(
            StorageQuotaIntegrationTest.rootAdmin, "quota_reservations"));
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "createAttachmentUploadUrl",
                    uploads.initiate(request("key").toBuilder().setSizeBytes(11).build()))
                .block(WAIT));
    var stored =
        as(A, ACTOR, "confirmAttachmentUpload", store.staging(a.getObjectKey())).block(WAIT);
    var reservation =
        as(
                A,
                ACTOR,
                "confirmAttachmentUpload",
                StorageQuotaIntegrationTest.authorityClient.get(stored.operation()))
            .block(WAIT);
    assertThrows(
        RuntimeException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "confirmAttachmentUpload",
                    StorageQuotaIntegrationTest.authorityClient.release(reservation))
                .block(WAIT));
    assertThrows(DomainException.class, () -> begin("key"));
  }

  @Test
  void concurrentConfirmKeepsSinglePinnedCopyAndSingleCommit() throws Exception {
    var u = begin("race");
    put(u);
    var results =
        Flux.range(0, 6)
            .flatMap(
                i -> as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))), 6)
            .collectList()
            .block(Duration.ofSeconds(60));
    assertEquals(1, results.stream().map(r -> r.getId()).distinct().count());
    assertEquals(1, rows("issue_attachments"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void foreignActorTenantIssueAndForgedKeyCannotConfirm() throws Exception {
    var u = begin("private");
    put(u);
    var q = confirmation(u);
    UUID other = UUID.randomUUID();
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    other,
                    "confirmAttachmentUpload",
                    uploads.confirm(q.toBuilder().setActorUserId(other.toString()).build()))
                .block(WAIT));
    assertThrows(
        DomainException.class,
        () -> as(B, ACTOR, "confirmAttachmentUpload", uploads.confirm(q)).block(WAIT));
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "confirmAttachmentUpload",
                    uploads.confirm(q.toBuilder().setIssueId(UUID.randomUUID().toString()).build()))
                .block(WAIT));
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "confirmAttachmentUpload",
                    uploads.confirm(q.toBuilder().setObjectKey("foreign/key").build()))
                .block(WAIT));
    assertEquals(0, rows("issue_attachments"));
    assertTrue(
        !as(A, ACTOR, "confirmAttachmentUpload", store.staging(u.getObjectKey()))
            .block(WAIT)
            .confirmRequested());
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void databaseFailureAfterRootCommitIsRecoveredWithoutDoubleUsage() throws Exception {
    var u = begin("activation-fault");
    put(u);
    sql(
        "ALTER TABLE taska.issue_attachments ADD CONSTRAINT reject_attachment CHECK(false) NOT"
            + " VALID");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u)))
                  .block(WAIT));
    } finally {
      sql("ALTER TABLE taska.issue_attachments DROP CONSTRAINT reject_attachment");
    }
    assertEquals(0, rows("issue_attachments"));
    assertEquals(
        0L,
        StorageQuotaIntegrationTest.ownerAdmin
            .sql(
                "SELECT count(*) FROM taska.storage_quota_outbox WHERE"
                    + " event_type='AttachmentConfirmed'")
            .map((r, m) -> r.get(0, Long.class))
            .one()
            .block(WAIT));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
    as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT);
    assertEquals(1, rows("issue_attachments"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void failedPinAfterS3CopyReusesExistingOwnedVersion() throws Exception {
    var u = begin("copy-fault");
    put(u);
    sql(
        "ALTER TABLE taska.attachment_uploads ADD CONSTRAINT reject_pin CHECK(final_version IS"
            + " NULL) NOT VALID");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u)))
                  .block(WAIT));
    } finally {
      sql("ALTER TABLE taska.attachment_uploads DROP CONSTRAINT reject_pin");
    }
    as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT);
    var row = as(A, ACTOR, "confirmAttachmentUpload", store.staging(u.getObjectKey())).block(WAIT);
    var versions =
        StorageQuotaIntegrationTest.s3
            .listObjectVersions(b -> b.bucket(BUCKET).prefix(row.finalKey()))
            .get();
    assertEquals(
        1, versions.versions().stream().filter(v -> v.key().equals(row.finalKey())).count());
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void sizeAndMimeMismatchDoNotDeleteOrChargeObjects() throws Exception {
    var u = begin("oversized");
    StorageQuotaIntegrationTest.s3
        .putObject(
            b -> b.bucket(BUCKET).key(u.getObjectKey()).contentType("text/plain"),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(new byte[11]))
        .get();
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT));
    assertEquals(
        11,
        StorageQuotaIntegrationTest.s3
            .headObject(b -> b.bucket(BUCKET).key(u.getObjectKey()))
            .get()
            .contentLength());
    assertEquals(0, rows("issue_attachments"));
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void disabledUploadsDenyNewCapabilitiesButAcceptedConfirmCanFinish() throws Exception {
    var u = begin("accepted");
    put(u);
    StorageQuotaIntegrationTest.enabled.set(false);
    assertThrows(io.grpc.StatusRuntimeException.class, () -> begin("disabled"));
    as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT);
    assertEquals(1, rows("issue_attachments"));
  }

  @Test
  void expiredConfirmAndRevokedAclCannotWriteAttachment() throws Exception {
    var u = begin("expiry");
    put(u);
    StorageQuotaIntegrationTest.permitted.set(false);
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT));
    StorageQuotaIntegrationTest.permitted.set(true);
    StorageQuotaIntegrationTest.time.set(StorageQuotaIntegrationTest.time.get().plusSeconds(901));
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT));
    assertEquals(0, rows("issue_attachments"));
  }

  @Test
  void adminBypassStillRecordsActualUsageAndDoesNotIgnoreObjectIntegrity() throws Exception {
    StorageQuotaIntegrationTest.limit.set(1);
    var quotaError = assertThrows(io.grpc.StatusRuntimeException.class, () -> begin("ordinary"));
    var details = mapper.GrpcErrorDetails.decode(quotaError.getTrailers());
    assertEquals("QUOTA_EXCEEDED", details.get("code"));
    assertEquals("STORAGE_BYTES", details.get("quota.kind"));
    assertEquals("1", details.get("quota.limit"));
    StorageQuotaIntegrationTest.bypass.set(true);
    var u = begin("admin");
    put(u);
    as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT);
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
    assertEquals(1, rows("issue_attachments"));
  }

  @Test
  void readOnlyContextCannotInitiateOrConfirm() {
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setTenantId(A.toString())
            .setAction("createAttachmentUploadUrl")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
            .addScopes("read")
            .build();
    assertThrows(
        DomainException.class,
        () ->
            RegistryTestContext.withClaims(claims, uploads.initiate(request("read-only")))
                .block(WAIT));
    assertEquals(0, rows("attachment_uploads"));
  }

  <T> Mono<T> recover(Mono<T> work, boolean valid) {
    var claims =
        TrustedTenantContext.newBuilder()
            .setTenantId(A.toString())
            .setAction("reconcileAttachmentUploads")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setIssuerService("issue-service")
            .setServiceIdentity("issue-service")
            .setServicePurpose(valid ? "STORAGE_CLEANUP" : "PROJECT_CREATION")
            .setActorGeneration("1")
            .addScopes("read")
            .addScopes("write")
            .addScopes("storage:recover")
            .build();
    return RegistryTestContext.withClaims(claims, work);
  }

  @Test
  void boundedRecoveryFinishesConfirmedIntentAfterLostActivation() throws Exception {
    var u = begin("recover-activation");
    put(u);
    StorageQuotaIntegrationTest.failOpen.set(true);
    assertThrows(
        RuntimeException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(u))).block(WAIT));
    StorageQuotaIntegrationTest.failOpen.set(false);
    var result = recover(uploads.reconcile(null, 100), true).block(WAIT);
    assertEquals(1, result.processed());
    assertEquals(0, result.failed());
    assertEquals(1, rows("issue_attachments"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
    assertThrows(
        DomainException.class, () -> recover(uploads.reconcile(null, 100), false).block(WAIT));
    assertThrows(
        DomainException.class,
        () -> as(A, ACTOR, "reconcileAttachmentUploads", uploads.reconcile(null, 100)).block(WAIT));
  }

  @Test
  void recoveryCancelsOnlyExpiredOwnManifestAndRetainsUnknownBytes() throws Exception {
    var u = begin("expire-cleanup");
    put(u);
    String foreign = "foreign/keep";
    StorageQuotaIntegrationTest.s3
        .putObject(
            b -> b.bucket(BUCKET).key(foreign),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(new byte[1]))
        .get();
    StorageQuotaIntegrationTest.time.set(StorageQuotaIntegrationTest.time.get().plusSeconds(901));
    StorageQuotaIntegrationTest.objectOnline.set(false);
    var unavailable = recover(uploads.reconcile(null, 100), true).block(WAIT);
    assertEquals(1, unavailable.failed());
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getReserved());
    StorageQuotaIntegrationTest.objectOnline.set(true);
    var cleaned = recover(uploads.reconcile(null, 100), true).block(WAIT);
    assertEquals(0, cleaned.failed());
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getReserved());
    assertEquals(0, rows("issue_attachments"));
    assertEquals(
        1,
        StorageQuotaIntegrationTest.s3
            .headObject(b -> b.bucket(BUCKET).key(foreign))
            .get()
            .contentLength());
  }

  @Test
  void disabledBucketVersioningNeverIssuesPutCapability() throws Exception {
    StorageQuotaIntegrationTest.s3
        .putBucketVersioning(
            b ->
                b.bucket(BUCKET)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.SUSPENDED)))
        .get();
    try {
      assertThrows(DomainException.class, () -> begin("no-versioning"));
    } finally {
      StorageQuotaIntegrationTest.s3
          .putBucketVersioning(
              b ->
                  b.bucket(BUCKET)
                      .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)))
          .get();
    }
    assertEquals(0, rows("issue_attachments"));
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void unknownCopyCannotBeResubmittedOrReleaseReservedBytes() throws Exception {
    var capability = begin("unknown-copy");
    put(capability);
    var u =
        as(A, ACTOR, "confirmAttachmentUpload", store.staging(capability.getObjectKey()))
            .block(WAIT);
    var storage =
        new S3UploadObjects(
            StorageQuotaIntegrationTest.s3,
            signer,
            StorageQuotaIntegrationTest.objectStore,
            BUCKET,
            StorageQuotaIntegrationTest.clock);
    var version = storage.stagingVersion(u.stagingKey(), u.mime(), u.bytes()).block(WAIT);
    as(A, ACTOR, "confirmAttachmentUpload", store.pin(u, true, version)).block(WAIT);
    assertTrue(as(A, ACTOR, "confirmAttachmentUpload", store.claimCopy(u)).block(WAIT));
    // Имитация потери ответа COPY: повтор не отправляет ещё один COPY и не освобождает квоту.
    assertThrows(
        RuntimeException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(capability)))
                .block(WAIT));
    assertTrue(
        StorageQuotaIntegrationTest.s3
            .listObjectVersions(b -> b.bucket(BUCKET).prefix(u.finalKey()))
            .get()
            .versions()
            .isEmpty());
    var reservation =
        as(
                A,
                ACTOR,
                "confirmAttachmentUpload",
                StorageQuotaIntegrationTest.authorityClient.get(u.operation()))
            .block(WAIT);
    assertThrows(
        RuntimeException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "confirmAttachmentUpload",
                    StorageQuotaIntegrationTest.authorityClient.release(reservation))
                .block(WAIT));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getReserved());
    // В том числе старое состояние EXPIRED не служит доказательством отсутствия позднего COPY.
    sql("UPDATE taska.attachment_object_manifest SET state='EXPIRED' WHERE id='" + u.id() + "'");
    var unknown =
        assertThrows(
            io.grpc.StatusRuntimeException.class,
            () ->
                as(
                        A,
                        ACTOR,
                        "confirmAttachmentUpload",
                        StorageQuotaIntegrationTest.authorityClient.release(reservation))
                    .block(WAIT));
    assertEquals(io.grpc.Status.Code.UNAVAILABLE, unknown.getStatus().getCode());
    assertTrue(unknown.getStatus().getDescription().contains("COPY_OUTCOME_UNKNOWN"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getReserved());
    // Позднее положительное доказательство COPY позволяет безопасно убрать обе собственные версии.
    storage
        .copyPinned(u.stagingKey(), version, u.finalKey(), u.operation().toString(), u.mime())
        .block(WAIT);
    StorageQuotaIntegrationTest.time.set(StorageQuotaIntegrationTest.time.get().plusSeconds(901));
    assertEquals(0, recover(uploads.reconcile(null, 100), true).block(WAIT).failed());
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getReserved());
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
    assertEquals(0, rows("issue_attachments"));
  }

  @Test
  void stagingOverwriteCannotReplaceAlreadyPinnedContent() throws Exception {
    var capability = begin("overwrite-after-pin");
    put(capability);
    var u =
        as(A, ACTOR, "confirmAttachmentUpload", store.staging(capability.getObjectKey()))
            .block(WAIT);
    var version =
        StorageQuotaIntegrationTest.s3
            .headObject(b -> b.bucket(BUCKET).key(u.stagingKey()))
            .get()
            .versionId();
    as(A, ACTOR, "confirmAttachmentUpload", store.pin(u, true, version)).block(WAIT);
    StorageQuotaIntegrationTest.s3
        .putObject(
            b -> b.bucket(BUCKET).key(u.stagingKey()).contentType("text/plain"),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(
                "ABCDEFGHIJ".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .get();
    var attachment =
        as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(capability)))
            .block(WAIT);
    assertEquals(
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[10])),
        attachment.getChecksum());
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void mimeMismatchCannotConfirmOrDeleteStaging() throws Exception {
    var capability = begin("wrong-mime");
    StorageQuotaIntegrationTest.s3
        .putObject(
            b -> b.bucket(BUCKET).key(capability.getObjectKey()).contentType("image/png"),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(new byte[10]))
        .get();
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(capability)))
                .block(WAIT));
    assertEquals(
        "image/png",
        StorageQuotaIntegrationTest.s3
            .headObject(b -> b.bucket(BUCKET).key(capability.getObjectKey()))
            .get()
            .contentType());
    assertEquals(0, rows("issue_attachments"));
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void administrativeBypassCannotConfirmMissingObject() {
    StorageQuotaIntegrationTest.limit.set(1);
    StorageQuotaIntegrationTest.bypass.set(true);
    var capability = begin("admin-missing");
    assertThrows(
        RuntimeException.class,
        () ->
            as(A, ACTOR, "confirmAttachmentUpload", uploads.confirm(confirmation(capability)))
                .block(WAIT));
    assertEquals(0, rows("issue_attachments"));
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getUsed());
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getReserved());
  }

  @Test
  void quotaRootActivationPersistsAttachmentWithoutPublicFallback() throws Exception {
    var capability = begin("root-activation");
    put(capability);
    var u =
        as(A, ACTOR, "confirmAttachmentUpload", store.staging(capability.getObjectKey()))
            .block(WAIT);
    var reservation =
        as(
                A,
                ACTOR,
                "confirmAttachmentUpload",
                StorageQuotaIntegrationTest.authorityClient.get(u.operation()))
            .block(WAIT);
    var storage =
        new S3UploadObjects(
            StorageQuotaIntegrationTest.s3,
            signer,
            StorageQuotaIntegrationTest.objectStore,
            BUCKET,
            StorageQuotaIntegrationTest.clock);
    var stage = storage.stagingVersion(u.stagingKey(), u.mime(), u.bytes()).block(WAIT);
    as(A, ACTOR, "confirmAttachmentUpload", store.pin(u, true, stage)).block(WAIT);
    assertTrue(as(A, ACTOR, "confirmAttachmentUpload", store.claimCopy(u)).block(WAIT));
    var finalVersion =
        storage
            .copyPinned(u.stagingKey(), stage, u.finalKey(), u.operation().toString(), u.mime())
            .block(WAIT);
    as(A, ACTOR, "confirmAttachmentUpload", store.pin(u, false, finalVersion)).block(WAIT);
    as(
            A,
            ACTOR,
            "confirmAttachmentUpload",
            StorageQuotaIntegrationTest.owner.materialize(reservation, stage, finalVersion))
        .block(WAIT);
    as(
            A,
            ACTOR,
            "confirmAttachmentUpload",
            StorageQuotaIntegrationTest.authorityClient.commit(reservation))
        .block(WAIT);
    assertEquals(1, rows("issue_attachments"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
    var response = as(A, ACTOR, "confirmAttachmentUpload", store.result(u)).block(WAIT);
    assertEquals(10, response.getSizeBytes());
    assertEquals(
        1L,
        StorageQuotaIntegrationTest.ownerAdmin
            .sql(
                "SELECT count(*) FROM taska.storage_quota_outbox WHERE"
                    + " event_type='AttachmentConfirmed'")
            .map((r, m) -> r.get(0, Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void repeatedActivationWithoutUploadIntentPreservesExistingQuotaProtocol() throws Exception {
    var reservation = StorageQuotaIntegrationTest.reserve(10);
    StorageQuotaIntegrationTest.upload(reservation, 7);
    var committed = StorageQuotaIntegrationTest.commit(reservation);
    StorageQuotaIntegrationTest.commit(reservation);
    assertEquals(
        ru.taska.quota.StorageQuotaProtocol.OwnerState.CONFIRMED,
        as(
                A,
                ACTOR,
                "confirmAttachmentUpload",
                StorageQuotaIntegrationTest.owner.activate(committed))
            .block(WAIT)
            .state());
    assertEquals(0, rows("attachment_uploads"));
    assertEquals(0, rows("issue_attachments"));
    assertEquals("7", StorageQuotaIntegrationTest.usage(A).getUsed());
  }

  @Test
  void recoverySeparatesCorruptionFromTemporaryRootFailure() {
    begin("recovery-diagnostics");
    var authority = org.mockito.Mockito.mock(ru.taska.quota.StorageQuotaProtocol.Authority.class);
    var isolated =
        new AttachmentUploads(
            store,
            StorageQuotaIntegrationTest.owner,
            authority,
            null,
            (p, i, u, phase) -> Mono.error(new IllegalStateException("Unexpected policy call")),
            StorageQuotaIntegrationTest.clock);
    org.mockito.Mockito.when(authority.get(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenReturn(
            Mono.error(
                io.grpc.Status.DATA_LOSS
                    .withDescription("OWNED_ADMISSION_CORRUPT")
                    .asRuntimeException()));
    var corrupt = recover(isolated.reconcile(null, 100), true).block(WAIT);
    assertEquals(1, corrupt.processed());
    assertEquals(1, corrupt.failed());
    assertEquals(1, corrupt.dataLoss());
    org.mockito.Mockito.when(authority.get(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenReturn(Mono.error(io.grpc.Status.UNAVAILABLE.asRuntimeException()));
    var temporary = recover(isolated.reconcile(null, 100), true).block(WAIT);
    assertEquals(1, temporary.failed());
    assertEquals(0, temporary.dataLoss());
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getReserved());
    assertEquals(0, rows("issue_attachments"));
  }

  @Test
  void sameCredentialSharesOngoingSagaButEveryCallRequiresCurrentAccess() throws Exception {
    var capability = begin("shared-confirm");
    put(capability);
    var authority = org.mockito.Mockito.spy(StorageQuotaIntegrationTest.authorityClient);
    var shared =
        new AttachmentUploads(
            store,
            StorageQuotaIntegrationTest.owner,
            authority,
            new S3UploadObjects(
                StorageQuotaIntegrationTest.s3,
                signer,
                StorageQuotaIntegrationTest.objectStore,
                BUCKET,
                StorageQuotaIntegrationTest.clock),
            policy,
            StorageQuotaIntegrationTest.clock);
    var invocation =
        as(A, ACTOR, "confirmAttachmentUpload", shared.confirm(confirmation(capability)));
    var results = Flux.range(0, 6).flatMap(i -> invocation, 6).collectList().block(WAIT);
    assertEquals(1, results.stream().map(r -> r.getId()).distinct().count());
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.times(1))
        .commit(org.mockito.ArgumentMatchers.any());
    assertEquals(1, rows("issue_attachments"));
    assertEquals("10", StorageQuotaIntegrationTest.usage(A).getUsed());
    StorageQuotaIntegrationTest.permitted.set(false);
    assertThrows(DomainException.class, () -> invocation.block(WAIT));
    org.mockito.Mockito.verify(authority, org.mockito.Mockito.times(1))
        .commit(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void disallowedMimeDoesNotPersistIntentOrConsumeReservation() {
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    "createAttachmentUploadUrl",
                    uploads.initiate(
                        request("blocked-mime").toBuilder()
                            .setContentType("application/x-msdownload")
                            .build()))
                .block(WAIT));
    assertEquals(0, rows("attachment_uploads"));
    assertEquals(
        0,
        StorageQuotaIntegrationTest.rows(
            StorageQuotaIntegrationTest.rootAdmin, "quota_reservations"));
    assertEquals("0", StorageQuotaIntegrationTest.usage(A).getReserved());
  }
}
