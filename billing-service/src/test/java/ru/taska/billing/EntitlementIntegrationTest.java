package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextBridge;

@Testcontainers
@Timeout(90)
class EntitlementIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final SubscriptionIntegrationTest f = new SubscriptionIntegrationTest();
  EntitlementStore entitlements;
  Set<String> members;
  AtomicBoolean readOnline;

  @BeforeAll
  static void setupEntitlements() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void closeEntitlements() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void resetEntitlements() {
    f.reset();
    members = ConcurrentHashMap.newKeySet();
    members.addAll(List.of(OWNER_A.toString(), OTHER.toString()));
    readOnline = new AtomicBoolean(true);
    entitlements =
        new EntitlementStore(
            f.transactions,
            v -> {
              if (!readOnline.get())
                return Mono.error(
                    CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
              var claims = RpcContextBridge.details(v).getContext();
              return Mono.just(
                  claims.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE
                      || (A.toString().equals(v.tenantId())
                          && members.contains(v.actorId())
                          && "1".equals(claims.getMembershipGeneration())));
            },
            f.clock);
    f.store =
        new SubscriptionStore(
            f.transactions,
            f.readers,
            f.evidence,
            f.clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), f.clock),
            entitlements);
  }

  @AfterEach
  void noLeak() {
    f.scopeDoesNotLeak();
  }

  Entitlement get(UUID actor, UUID tenant) {
    return RegistryTestContext.withClaims(
            f.human(actor, tenant, "getEntitlement"),
            entitlements.get(
                GetEntitlementRpcRequest.newBuilder().setTenantId(tenant.toString()).build()))
        .block(WAIT);
  }

  Mono<Entitlement> internal(UUID tenant, String minimum) {
    var request = GetEntitlementSnapshotRequest.newBuilder().setTenantId(tenant.toString());
    if (minimum != null) request.setMinimumVersion(minimum);
    return RegistryTestContext.withClaims(
        f.worker(tenant, "getEntitlementSnapshot", "issue-service").toBuilder()
            .clearScopes()
            .addScopes("read")
            .build(),
        entitlements.getSnapshot(request.build()));
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  void rejects(DomainStatus status, Mono<?> operation) {
    assertEquals(
        status, assertThrows(DomainException.class, () -> operation.block(WAIT)).getStatus());
  }

  @Test
  void freeAndPendingHaveExplicitDifferentRightsWithoutFinancialData() {
    f.init(A, FREE, "free").block(WAIT);
    f.init(B, PAID, "paid").block(WAIT);
    var free = get(OTHER, A);
    var pending = internal(B, null).block(WAIT);
    assertEquals(FREE.toString(), free.getPlanVersionId());
    assertTrue(free.getRestrictionsList().isEmpty());
    assertFalse(free.hasPaidThrough());
    assertFalse(free.hasCoveragePeriodId());
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PENDING_FIRST_PAYMENT),
        pending.getRestrictionsList());
    assertFalse(pending.hasPaidThrough());
    assertEquals(
        "50",
        free.getLimitsList().stream()
            .filter(q -> q.getKind() == QuotaLimit.KindValue.KIND_TASK_LIFETIME)
            .findFirst()
            .orElseThrow()
            .getLimit());
    assertEquals(2, count("entitlement_snapshots"));
    assertEquals(free, get(OTHER, A));
    assertEquals(2, count("entitlement_snapshots"));
    assertThrows(DomainException.class, () -> f.get(OTHER, A));
  }

  @Test
  void exactExpiryCreatesOneNewVersionAcrossConcurrentReads() {
    f.init(A, PAID, "init").block(WAIT);
    Instant end = NOW.plusSeconds(86400);
    UUID proof = f.proof(A, NOW, end);
    f.grant(A, proof, "paid").block(WAIT);
    var paid = get(OTHER, A);
    assertTrue(paid.getRestrictionsList().isEmpty());
    assertEquals(end.toString(), paid.getPaidThrough());
    assertEquals(end.toString(), paid.getValidUntil());
    f.time.set(end.minusNanos(1000));
    assertEquals(paid, get(OTHER, A));
    f.time.set(end);
    var expired =
        Flux.range(0, 16)
            .flatMap(
                i ->
                    Mono.fromCallable(() -> get(OTHER, A)).subscribeOn(Schedulers.boundedElastic()),
                16)
            .collectList()
            .block(WAIT);
    assertEquals(1, expired.stream().distinct().count());
    var result = expired.getFirst();
    assertTrue(Long.parseLong(result.getVersion()) > Long.parseLong(paid.getVersion()));
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED), result.getRestrictionsList());
    assertEquals(end.toString(), result.getPaidThrough());
    assertEquals(3, count("entitlement_snapshots"));
    assertEquals(
        3,
        operator
            .sql("SELECT count(*) n FROM taska.outbox_events WHERE aggregate_type='Entitlement'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void futureCoverageDoesNotApplyEarlyAndItsStartIsAHardCacheBoundary() {
    f.init(A, PAID, "init").block(WAIT);
    UUID first = f.proof(A, NOW, NOW.plusSeconds(100));
    UUID next = f.proof(A, NOW.plusSeconds(50), NOW.plusSeconds(200));
    f.grant(A, first, "first").block(WAIT);
    f.grant(A, next, "next").block(WAIT);
    var before = get(OWNER_A, A);
    assertEquals(
        f.confirmed.get(first).activationOperationId().toString(), before.getCoveragePeriodId());
    assertEquals(NOW.plusSeconds(50).toString(), before.getValidUntil());
    f.time.set(NOW.plusSeconds(50));
    var after = get(OWNER_A, A);
    assertEquals(
        f.confirmed.get(next).activationOperationId().toString(), after.getCoveragePeriodId());
    assertEquals(NOW.plusSeconds(200).toString(), after.getPaidThrough());
    assertTrue(Long.parseLong(after.getVersion()) > Long.parseLong(before.getVersion()));
    assertEquals(after, get(OWNER_A, A));
  }

  @Test
  void futurePlanTermsRemainBoundToTheirPeriodAfterActivationAndExpiry() {
    f.init(A, PAID, "init").block(WAIT);
    UUID current = f.proof(A, NOW, NOW.plusSeconds(100));
    f.grant(A, current, "current").block(WAIT);
    UUID plan = UUID.randomUUID();
    sql(
        "INSERT INTO taska.catalog_plan_versions SELECT"
            + " (jsonb_populate_record(NULL::taska.catalog_plan_versions,to_jsonb(p)||jsonb_build_object('id','"
            + plan
            + "','version',2,'max_projects',9,'features',jsonb_build_array('new-feature')))).* FROM"
            + " taska.catalog_plan_versions p WHERE id='"
            + PAID
            + "'");
    UUID proof = UUID.randomUUID(), invoice = UUID.randomUUID();
    InvoiceTestFixtures.ensure(operator, A, invoice, plan);
    f.confirmed.put(
        proof,
        new SubscriptionDependencies.CoverageEvidence(
            A,
            invoice,
            UUID.randomUUID(),
            plan,
            UUID.randomUUID(),
            NOW.plusSeconds(50),
            NOW.plusSeconds(200),
            31,
            LocalTime.of(12, 34, 56),
            NOW,
            SubscriptionDependencies.PaymentSource.PSP,
            List.of()));
    f.grant(A, proof, "future-plan").block(WAIT);
    assertEquals(PAID.toString(), get(OWNER_A, A).getPlanVersionId());
    f.time.set(NOW.plusSeconds(50));
    var active = get(OWNER_A, A);
    assertEquals(plan.toString(), active.getPlanVersionId());
    assertEquals(List.of("new-feature"), active.getFeaturesList());
    f.time.set(NOW.plusSeconds(200));
    var expired = get(OWNER_A, A);
    assertTrue(
        expired.getRestrictionsList().contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED));
    assertEquals(plan.toString(), expired.getPlanVersionId());
    assertEquals(active.getLimitsList(), expired.getLimitsList());
  }

  @Test
  void d251RevokesOnlyLinkedCoverageAndDuplicateOrReorderedFactsCannotRestoreIt() {
    f.init(A, PAID, "init").block(WAIT);
    UUID old = f.proof(A, NOW, NOW.plusSeconds(100));
    UUID independent = f.proof(A, NOW, NOW.plusSeconds(200));
    f.grant(A, old, "old").block(WAIT);
    f.grant(A, independent, "independent").block(WAIT);
    sql(
        "UPDATE taska.subscriptions SET autopay_state='CONSENTED',renewal_enabled=true WHERE"
            + " tenant_id='"
            + A
            + "'");
    var before = get(OWNER_A, A);
    UUID reversal = f.reverse(f.confirmed.get(old));
    f.revoke(A, reversal, "loss").block(WAIT);
    var after = get(OWNER_A, A);
    assertEquals(before.getCoveragePeriodId(), after.getCoveragePeriodId());
    assertEquals(NOW.plusSeconds(200).toString(), after.getPaidThrough());
    assertTrue(after.getRestrictionsList().isEmpty());
    f.revoke(A, reversal, "duplicate-loss").block(WAIT);
    f.grant(A, old, "reordered-old-payment").block(WAIT);
    assertEquals(after, get(OWNER_A, A));
    assertEquals(
        Subscription.AutopayStateValue.AUTOPAY_STATE_DISABLED, f.get(OWNER_A, A).getAutopayState());
    f.revoke(A, f.reverse(f.confirmed.get(independent)), "last-loss").block(WAIT);
    var revoked = get(OWNER_A, A);
    assertFalse(revoked.hasPaidThrough());
    assertTrue(
        revoked.getRestrictionsList().contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED));
    UUID fresh = f.proof(A, NOW, NOW.plusSeconds(300));
    f.grant(A, fresh, "new-full-payment").block(WAIT);
    assertEquals(NOW.plusSeconds(300).toString(), get(OWNER_A, A).getPaidThrough());
    assertEquals(
        Subscription.AutopayStateValue.AUTOPAY_STATE_DISABLED, f.get(OWNER_A, A).getAutopayState());
  }

  @Test
  void snapshotOutboxFailureRollsBackCoverageSnapshotVersionAndReceipt() {
    f.init(A, PAID, "init").block(WAIT);
    var before = get(OWNER_A, A);
    UUID proof = f.proof(A, NOW, NOW.plusSeconds(100));
    sql(
        "CREATE FUNCTION taska.fixture_ent_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.event_type='EntitlementSnapshotChanged' THEN RAISE EXCEPTION 'fixture'; END IF;"
            + " RETURN NEW; END $$");
    sql(
        "CREATE TRIGGER fixture_ent_fail BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE"
            + " FUNCTION taska.fixture_ent_fail()");
    try {
      assertThrows(RuntimeException.class, () -> f.grant(A, proof, "retry").block(WAIT));
      assertEquals(0, count("subscription_periods"));
      assertEquals(1, count("entitlement_snapshots"));
      assertEquals(1, count("subscription_command_receipts"));
      assertEquals(2, count("outbox_events"));
      assertEquals(before, get(OWNER_A, A));
    } finally {
      sql("DROP TRIGGER fixture_ent_fail ON taska.outbox_events");
      sql("DROP FUNCTION taska.fixture_ent_fail()");
    }
    f.grant(A, proof, "retry").block(WAIT);
    assertEquals(2, count("entitlement_snapshots"));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void missingStaleOrUnauthorizedContextCannotCreateAnAllowOrDefaultScope() {
    f.init(A, FREE, "init").block(WAIT);
    var request = GetEntitlementRpcRequest.newBuilder().setTenantId(A.toString()).build();
    rejects(DomainStatus.UNAUTHENTICATED, entitlements.get(request));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        RegistryTestContext.withClaims(
            f.human(OWNER_A, B, "getEntitlement"), entitlements.get(request)));
    members.remove(OTHER.toString());
    assertThrows(DomainException.class, () -> get(OTHER, A));
    readOnline.set(false);
    rejects(DomainStatus.UNAVAILABLE, internal(A, null));
    readOnline.set(true);
    rejects(DomainStatus.UNAVAILABLE, internal(A, "999"));
    rejects(DomainStatus.NOT_FOUND, internal(B, null));
    assertEquals(1, count("entitlement_snapshots"));
    assertEquals(2, count("outbox_events"));
  }

  @Test
  void runtimeRlsCompositeFkAndAppendOnlyHistoryRejectForeignOrMutableSnapshots() {
    f.init(A, FREE, "a").block(WAIT);
    f.init(B, PAID, "b").block(WAIT);
    var visible =
        f.transactions.execute(
            client ->
                client
                    .sql("SELECT tenant_id FROM taska.entitlement_snapshots")
                    .map((r, m) -> r.get("tenant_id", UUID.class))
                    .all()
                    .collectList());
    assertEquals(List.of(A), f.asWorker(A, "fixture", "billing-service", visible).block(WAIT));
    assertEquals(
        0L,
        db.sql("SELECT count(*) n FROM taska.entitlement_snapshots")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    for (String statement :
        List.of(
            "UPDATE taska.entitlement_snapshots SET max_projects=999",
            "DELETE FROM taska.entitlement_snapshots")) {
      assertThrows(
          RuntimeException.class,
          () ->
              f.asWorker(
                      A,
                      "fixture",
                      "billing-service",
                      f.transactions.execute(client -> client.sql(statement).fetch().rowsUpdated()))
                  .block(WAIT));
      assertThrows(RuntimeException.class, () -> sql(statement));
    }
    var pending = internal(B, null).block(WAIT);
    assertEquals("1", pending.getVersion());
    assertEquals("1", get(OWNER_A, A).getVersion());
  }

  @Test
  void existingSubscriptionIsMaterializedWithoutAFreeFallbackOrDuplicateRefresh() {
    UUID legacy = UUID.randomUUID();
    sql(
        "INSERT INTO"
            + " taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode)"
            + " SELECT '"
            + legacy
            + "',p.id,to_jsonb(p)||jsonb_build_object('kind',c.kind),'AWAITING_PAYMENT' FROM"
            + " taska.catalog_plan_versions p JOIN taska.catalog_plans c USING(plan_code) WHERE"
            + " p.id='"
            + PAID
            + "'");
    var snapshot = internal(legacy, null).block(WAIT);
    assertEquals("1", snapshot.getVersion());
    assertEquals(PAID.toString(), snapshot.getPlanVersionId());
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PENDING_FIRST_PAYMENT),
        snapshot.getRestrictionsList());
    assertEquals(snapshot, internal(legacy, "1").block(WAIT));
    assertEquals(1, count("entitlement_snapshots"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void foreignPeriodCannotBeReferencedByAnAppendOnlySnapshot() {
    f.init(A, FREE, "a").block(WAIT);
    f.init(B, PAID, "b").block(WAIT);
    UUID proof = f.proof(B, NOW, NOW.plusSeconds(100));
    f.grant(B, proof, "paid-b").block(WAIT);
    UUID foreign = f.confirmed.get(proof).activationOperationId();
    String statement =
        "INSERT INTO taska.entitlement_snapshots SELECT"
            + " (jsonb_populate_record(NULL::taska.entitlement_snapshots,to_jsonb(e)||jsonb_build_object('version',version+1,'source_operation_id',gen_random_uuid(),'period_id','"
            + foreign
            + "'))).* FROM taska.entitlement_snapshots e WHERE tenant_id='"
            + A
            + "' ORDER BY version DESC LIMIT 1";
    assertThrows(
        RuntimeException.class,
        () ->
            f.asWorker(
                    A,
                    "fixture",
                    "billing-service",
                    f.transactions.execute(client -> client.sql(statement).fetch().rowsUpdated()))
                .block(WAIT));
    assertEquals("1", get(OWNER_A, A).getVersion());
    assertEquals(3, count("entitlement_snapshots"));
  }

  @Test
  void independentlyRecordedDisputeSurvivesCoverageRevocationAndNewPayment() {
    f.init(A, PAID, "init").block(WAIT);
    UUID proof = f.proof(A, NOW, NOW.plusSeconds(100));
    f.grant(A, proof, "grant").block(WAIT);
    // Seed the immutable result of the future financial-case owner (TAS-518), not a caller flag.
    sql(
        "INSERT INTO taska.entitlement_snapshots SELECT"
            + " (jsonb_populate_record(NULL::taska.entitlement_snapshots,to_jsonb(e)||jsonb_build_object('version',version+1,'source_operation_id',gen_random_uuid(),'restrictions',jsonb_build_array('RESTRICTION_CODE_PAYMENT_DISPUTE')))).*"
            + " FROM taska.entitlement_snapshots e WHERE tenant_id='"
            + A
            + "' ORDER BY version DESC LIMIT 1");
    f.revoke(A, f.reverse(f.confirmed.get(proof)), "loss").block(WAIT);
    assertTrue(
        get(OWNER_A, A)
            .getRestrictionsList()
            .contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE));
    UUID fresh = f.proof(A, NOW, NOW.plusSeconds(200));
    f.grant(A, fresh, "new-payment").block(WAIT);
    var renewed = get(OWNER_A, A);
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE), renewed.getRestrictionsList());
    assertEquals(NOW.plusSeconds(200).toString(), renewed.getPaidThrough());
  }

  @Test
  void realMtlsUserAndServiceRpcsEnforceSignaturesAuthorityAndExpiry(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path certs) throws Exception {
    f.init(A, PAID, "init").block(WAIT);
    UUID proof = f.proof(A, NOW, NOW.plusSeconds(100));
    f.grant(A, proof, "grant").block(WAIT);
    BillingCatalogIntegrationTest.openssl(
        certs,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt",
        "-days",
        "1",
        "-subj",
        "/CN=tenant-test-ca");
    for (String name : List.of("api-gateway", "billing-service")) {
      BillingCatalogIntegrationTest.openssl(
          certs,
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=localhost");
      java.nio.file.Files.writeString(
          certs.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      BillingCatalogIntegrationTest.openssl(
          certs,
          "x509",
          "-req",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          name + ".crt",
          "-days",
          "1",
          "-extfile",
          name + ".ext");
    }

    var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new ru.taska.security.tenant.RpcContextSecurity.Keys() {
          public ru.taska.security.tenant.RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new ru.taska.security.tenant.RpcContextSecurity.SigningKey(
                "fixture", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return issuer.equals("api-gateway") && kid.equals("fixture") ? pair.getPublic() : null;
          }
        };
    var consumed = ConcurrentHashMap.<String>newKeySet();
    ru.taska.security.tenant.RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + ":" + jti));
    var online = new AtomicBoolean(true);
    ru.taska.security.tenant.RpcContextSecurity.Authority authority =
        (ctx, rpc, policy) ->
            Mono.just(online.get() && ctx.getContext().getIssuerService().equals("api-gateway"));
    var sender =
        new ru.taska.security.tenant.RpcContextSecurity(
            "api-gateway", Clock.systemUTC(), keys, replay, authority);
    var receiver =
        new ru.taska.security.tenant.RpcContextSecurity(
            "billing-service", Clock.systemUTC(), keys, replay, authority);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("security", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(
                beans.getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class), "test");
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                        certs.resolve("billing-service.crt").toFile(),
                        certs.resolve("billing-service.key").toFile())
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new BillingCatalogRpc(
                        org.mockito.Mockito.mock(BillingCatalog.class),
                        f.store,
                        org.mockito.Mockito.mock(InvoiceStore.class)),
                    interceptor))
            .addService(
                io.grpc.ServerInterceptors.intercept(new EntitlementRpc(entitlements), interceptor))
            .build()
            .start();
    var channel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("api-gateway.crt").toFile(),
                        certs.resolve("api-gateway.key").toFile())
                    .build())
            .build();

    try {
      var plain =
          BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
      var req =
          GetEntitlementRpcRequest.newBuilder()
              .setTenantId(A.toString())
              .setContext(wire(false))
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.getEntitlement(req))
              .getStatus()
              .getCode());
      var headers =
          BillingCatalogIntegrationTest.signed(
              sender,
              req,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
              BillingServiceGrpc.getGetEntitlementMethod().getFullMethodName());
      var stub =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
      var first = stub.getEntitlement(req);
      assertTrue(first.getRestrictionsList().isEmpty());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> stub.getEntitlement(req.toBuilder().setTenantId(B.toString()).build()))
              .getStatus()
              .getCode());
      var internalReq =
          GetEntitlementSnapshotRequest.newBuilder()
              .setTenantId(A.toString())
              .setMinimumVersion(first.getVersion())
              .setContext(wire(true))
              .build();
      var internalHeaders =
          BillingCatalogIntegrationTest.signed(
              sender,
              internalReq,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
              EntitlementServiceGrpc.getGetEntitlementSnapshotMethod().getFullMethodName());
      var serviceStub =
          EntitlementServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(internalHeaders));
      assertEquals(first, serviceStub.getEntitlementSnapshot(internalReq));
      f.time.set(NOW.plusSeconds(100));
      var expired = stub.getEntitlement(req);
      assertTrue(
          expired.getRestrictionsList().contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED));
      assertTrue(Long.parseLong(expired.getVersion()) > Long.parseLong(first.getVersion()));
      members.remove(OTHER.toString());
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.getEntitlement(req))
              .getStatus()
              .getCode());
      readOnline.set(false);
      assertEquals(
          io.grpc.Status.Code.UNAVAILABLE,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> serviceStub.getEntitlementSnapshot(internalReq))
              .getStatus()
              .getCode());
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private RequestContext wire(boolean service) {
    Instant now = Instant.now();
    var claims =
        service
            ? f.worker(A, "getEntitlementSnapshot", "api-gateway").toBuilder()
                .clearScopes()
                .addScopes("read")
            : f.human(OTHER, A, "getEntitlement").toBuilder();
    return RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            claims
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }
}
