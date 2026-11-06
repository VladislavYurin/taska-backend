package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListPlatformFlagsRpcRequest;
import ru.taska.api.b2b.v1.MerchantConfiguration;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.MerchantConfigurationView;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PublishMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.ResolveMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.commercial.CommercialConfigurationRules;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextSecurity;

@Testcontainers
@Timeout(90)
class MerchantConfigurationIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final Duration WAIT = Duration.ofSeconds(25);
  static final UUID ACTOR = UUID.randomUUID(), SESSION = UUID.randomUUID();
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static ConnectionPool pool;
  static DatabaseClient operator, db;
  static PlatformFlags service;
  static MerchantConfigurations merchant;

  static ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, pg.getHost())
            .option(ConnectionFactoryOptions.PORT, pg.getMappedPort(5432))
            .option(ConnectionFactoryOptions.DATABASE, pg.getDatabaseName())
            .option(ConnectionFactoryOptions.USER, user)
            .option(ConnectionFactoryOptions.PASSWORD, password)
            .build());
  }

  @BeforeAll
  static void setup() throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    var bootstrap =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            pg.getDatabaseName(),
            "-v",
            "service=tenant",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, bootstrap.getExitCode(), bootstrap.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
    sql("REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_tenant_app PASSWORD '" + APP_PASSWORD + "'");
    sql("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY_PASSWORD + "'");
    var lb =
        new TenantConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY_PASSWORD);
    // Upgrade the real preceding schema, preserving old changeset checksums.
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var log =
        json.readTree(
            MerchantConfigurationIntegrationTest.class.getResourceAsStream(
                "/db/changelog/db.changelog-master.yaml"));
    var changes = (tools.jackson.databind.node.ArrayNode) log.get("databaseChangeLog");
    while (changes.size() > 3) changes.remove(changes.size() - 1);
    for (var entry : changes)
      for (var change : entry.get("changeSet").get("changes")) {
        if (change.has("sqlFile") && change.get("sqlFile").has("relativeToChangelogFile")) {
          var f = (tools.jackson.databind.node.ObjectNode) change.get("sqlFile");
          f.put("relativeToChangelogFile", false);
          f.put("path", "db/changelog/" + f.get("path").asString());
        }
      }
    Path oldLog = Files.createTempFile("taska-flags-upgrade-", ".json");
    try {
      Files.writeString(oldLog, json.writeValueAsString(log));
      lb.setChangeLog(oldLog.toUri().toString());
      lb.afterPropertiesSet();
    } finally {
      Files.deleteIfExists(oldLog);
    }
    // The temporary changelog filename differs; pin the existing identities before applying current
    // log.
    sql(
        "UPDATE tenant_liquibase.databasechangelog SET"
            + " filename='db/changelog/db.changelog-master.yaml'");
    var checksums =
        operator
            .sql("SELECT id,md5sum FROM tenant_liquibase.databasechangelog ORDER BY id")
            .fetch()
            .all()
            .collectList()
            .block(WAIT);
    lb.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
    lb.afterPropertiesSet();
    lb.afterPropertiesSet();
    var priorIds =
        checksums.stream().map(r -> r.get("id")).collect(java.util.stream.Collectors.toSet());
    assertEquals(
        checksums,
        operator
            .sql("SELECT id,md5sum FROM tenant_liquibase.databasechangelog ORDER BY id")
            .fetch()
            .all()
            .filter(r -> priorIds.contains(r.get("id")))
            .collectList()
            .block(WAIT));
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP_PASSWORD))
                .maxSize(4)
                .initialSize(1)
                .build());
    db = DatabaseClient.create(pool);
    service =
        new PlatformFlags(
            db,
            TransactionalOperator.create(new R2dbcTransactionManager(pool)),
            new PlatformFlagCursor(
                Base64.getEncoder().encodeToString(new byte[32]), Clock.systemUTC()));
    merchant =
        new MerchantConfigurations(
            db, TransactionalOperator.create(new R2dbcTransactionManager(pool)), service);
  }

  static void sql(String sql) {
    operator.sql(sql).fetch().rowsUpdated().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void reset() {
    sql(
        "TRUNCATE"
            + " taska.platform_configuration_receipts,taska.platform_configuration_versions,taska.global_outbox_events");
    sql(
        "INSERT INTO taska.platform_configuration_versions SELECT"
            + " key,1,value_type,default_value,now(),NULL,now() FROM"
            + " taska.platform_configuration_definitions");
  }

  static <T> Mono<T> admin(String action, boolean modify, Mono<T> work) {
    return RegistryTestContext.withClaims(
        PlatformFlagsIntegrationTest.claims(modify).toBuilder().setAction(action).build(), work);
  }

  static <T> Mono<T> reader(Mono<T> work) {
    return RegistryTestContext.withClaims(
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setActorGeneration("service-v1")
            .setIssuerService("billing-service")
            .setAction("resolveMerchantConfiguration")
            .addScopes("read")
            .addScopes("commercial:resolve")
            .build(),
        work);
  }

  static PublishMerchantConfigurationRequest request(String version, MerchantConfiguration c) {
    return PublishMerchantConfigurationRequest.newBuilder()
        .setConfiguration(c)
        .setConfirmed(true)
        .setMutation(
            MutationOptions.newBuilder()
                .setExpectedVersion(version)
                .setIdempotencyKey(UUID.randomUUID().toString()))
        .build();
  }

  static Mono<MerchantConfigurationView> publish(PublishMerchantConfigurationRequest r) {
    return admin("publishMerchantConfiguration", true, merchant.publish(r));
  }

  static MerchantConfigurationSnapshot current() {
    return reader(merchant.resolve(ResolveMerchantConfigurationRequest.getDefaultInstance()))
        .block(WAIT);
  }

  static long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  static void rejects(DomainStatus status, Mono<?> work) {
    StepVerifier.create(work)
        .expectErrorSatisfies(
            e -> assertEquals(status, assertInstanceOf(DomainException.class, e).getStatus()))
        .verify(WAIT);
  }

  @Test
  void defaultLiveProfileIsDisabledAndNeverAppearsInScalarFlagsOrView() {
    var view = admin("getMerchantConfiguration", false, merchant.get()).block(WAIT);
    assertFalse(view.getEnabled());
    assertFalse(view.getLiveProfileComplete());
    assertTrue(view.getMissingRequirementsList().contains("SELLER_PROFILE"));
    var flags =
        admin(
                "listPlatformFlags",
                false,
                service.list(ListPlatformFlagsRpcRequest.getDefaultInstance()))
            .block(WAIT);
    assertEquals(24, flags.getItemsCount());
    assertTrue(
        flags.getItemsList().stream().noneMatch(f -> f.getKey().equals("MERCHANT_CONFIGURATION")));
    assertEquals("1", view.getVersion());
    assertFalse(view.toString().contains("secret://"));
  }

  @Test
  void publishStoresTypedImmutablePrivateSnapshotButOnlyMetadataInEvent() {
    var c = CommercialFixtures.profile();
    var view = publish(request("1", c)).block(WAIT);
    assertTrue(view.getLiveProfileComplete());
    assertEquals("2", view.getVersion());
    assertEquals(c, current().getConfiguration());
    var payload =
        operator
            .sql("SELECT payload::text AS p FROM taska.global_outbox_events")
            .map((r, m) -> r.get("p", String.class))
            .one()
            .block(WAIT);
    assertFalse(payload.contains("secret://"));
    assertFalse(payload.contains("TEST ONLY"));
    assertTrue(payload.contains(view.getEffectiveAt()));
    assertFalse(view.toString().contains("TEST-MERCHANT"));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "UPDATE taska.platform_configuration_versions SET value='{}' WHERE"
                        + " key='MERCHANT_CONFIGURATION'")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(1, count("platform_configuration_receipts"));
  }

  @Test
  void concurrentPublishReplaysOnceAndHistoricalSnapshotSurvivesChanges() {
    var r = request("1", CommercialFixtures.profile());
    var two =
        Mono.zip(
                publish(r).subscribeOn(Schedulers.parallel()),
                publish(r).subscribeOn(Schedulers.parallel()))
            .block(WAIT);
    assertEquals(two.getT1(), two.getT2());
    assertEquals(1, count("global_outbox_events"));
    publish(
            request(
                "2",
                r.getConfiguration().toBuilder()
                    .setSeller(
                        r.getConfiguration().getSeller().toBuilder()
                            .setLegalName("NEW TEST seller"))
                    .build()))
        .block(WAIT);
    assertEquals(two.getT1(), publish(r).block(WAIT));
    var old =
        reader(
                merchant.resolve(
                    ResolveMerchantConfigurationRequest.newBuilder().setVersion("2").build()))
            .block(WAIT);
    assertEquals(r.getConfiguration(), old.getConfiguration());
    assertEquals("NEW TEST seller", current().getConfiguration().getSeller().getLegalName());
    rejects(
        DomainStatus.ALREADY_EXISTS,
        publish(
            r.toBuilder()
                .setConfiguration(r.getConfiguration().toBuilder().setEnabled(false))
                .build()));
    rejects(DomainStatus.ABORTED, publish(request("1", r.getConfiguration())));
  }

  @Test
  void incompleteEnabledProfilesMixedCredentialsAndFixtureLiveCalendarAreRejectedWithoutWrites() {
    var c = CommercialFixtures.profile();
    for (var invalid :
        List.of(
            c.toBuilder().clearSeller().build(),
            c.toBuilder().clearTaxRules().build(),
            c.toBuilder().clearStripe().build(),
            c.toBuilder().clearBank().build(),
            c.toBuilder().clearFx().build(),
            c.toBuilder()
                .setStripe(c.getStripe().toBuilder().setApiKeyReference("sk_live_RAW_NOT_ALLOWED"))
                .build(),
            c.toBuilder()
                .setStripe(
                    c.getStripe().toBuilder()
                        .setEnvironment(CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX))
                .build(),
            c.toBuilder().setBank(c.getBank().toBuilder().setFixtureCalendar(true)).build(),
            c.toBuilder().setBank(c.getBank().toBuilder().setCalendarVerified(false)).build()))
      rejects(DomainStatus.INVALID_ARGUMENT, publish(request("1", invalid)));
    assertEquals("1", current().getVersion());
    assertEquals(0, count("platform_configuration_receipts"));
    assertEquals(0, count("global_outbox_events"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CommercialConfigurationRules.parse(
                "{\"schemaVersion\":1,\"environment\":\"COMMERCIAL_ENVIRONMENT_LIVE\",\"password\":\"raw-secret\"}"));
  }

  @Test
  void sandboxStripeDoesNotRequireInventedSellerOrLiveBankCalendar() {
    var c = CommercialFixtures.profile();
    var sandbox =
        c.toBuilder()
            .clearSeller()
            .clearBank()
            .clearMarkets()
            .addMarkets(
                CommercialFixtures.market(
                    ru.taska.api.b2b.v1.CommercialCurrency.COMMERCIAL_CURRENCY_EUR,
                    ru.taska.api.b2b.v1.CommercialMethod.COMMERCIAL_METHOD_PSP))
            .setEnvironment(CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX)
            .setStripe(
                c.getStripe().toBuilder()
                    .setEnvironment(CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX)
                    .setApiKeyReference("secret://sandbox/TEST-ONLY/api-key")
                    .setWebhookKeyReference("secret://sandbox/TEST-ONLY/webhook-key"))
            .build();
    var result = publish(request("1", sandbox)).block(WAIT);
    assertTrue(result.getEnabled());
    assertFalse(result.getLiveProfileComplete());
    assertFalse(current().getConfiguration().hasSeller());
  }

  @Test
  void tenantUserAndReadOnlyAdminCannotPublishOrReadPrivatePolicy() {
    var r = request("1", CommercialFixtures.profile());
    var claims =
        PlatformFlagsIntegrationTest.claims(true).toBuilder()
            .setAction("publishMerchantConfiguration");
    for (var c :
        List.of(
            claims.clone().setTenantId(UUID.randomUUID().toString()).build(),
            claims.clone().setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ).build(),
            claims.clone().setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT).build()))
      rejects(
          DomainStatus.PERMISSION_DENIED, RegistryTestContext.withClaims(c, merchant.publish(r)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        admin(
            "resolveMerchantConfiguration",
            false,
            merchant.resolve(ResolveMerchantConfigurationRequest.getDefaultInstance())));
    rejects(DomainStatus.FAILED_PRECONDITION, publish(r.toBuilder().setConfirmed(false).build()));
    assertEquals("1", current().getVersion());
  }

  @Test
  void outboxFailureRollsBackPolicyAuditAndReceipt() {
    sql(
        "ALTER TABLE taska.global_outbox_events ADD CONSTRAINT merchant_test_failure"
            + " CHECK(event_type<>'MerchantConfigurationChanged')");
    try {
      assertThrows(
          Exception.class, () -> publish(request("1", CommercialFixtures.profile())).block(WAIT));
    } finally {
      sql("ALTER TABLE taska.global_outbox_events DROP CONSTRAINT merchant_test_failure");
    }
    assertEquals("1", current().getVersion());
    assertEquals(0, count("platform_configuration_receipts"));
    assertEquals(0, count("global_outbox_events"));
  }

  @Test
  void realMtlsProtectsPublisherAndPrivateServiceReader(@TempDir Path certs) throws Exception {
    TenantRegistryIntegrationTest.openssl(
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
        "/CN=flags-ca");
    for (String name : List.of("api-gateway", "billing-service", "tenant-service")) {
      TenantRegistryIntegrationTest.openssl(
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
      Files.writeString(
          certs.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      TenantRegistryIntegrationTest.openssl(
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
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return java.util.Set.of("api-gateway", "billing-service").contains(issuer)
                    && kid.equals("test")
                ? pair.getPublic()
                : null;
          }
        };
    var allowed = new AtomicBoolean(true);
    RpcContextSecurity.Authority authority = (context, rpc, policy) -> Mono.just(allowed.get());
    var consumed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + jti));
    var recipient =
        new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, authority);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("security", recipient);
    var interceptor =
        new TenantConfiguration()
            .registrySecurity(beans.getBeanProvider(RpcContextSecurity.class), "test");
    var ssl =
        io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                certs.resolve("tenant-service.crt").toFile(),
                certs.resolve("tenant-service.key").toFile())
            .trustManager(certs.resolve("ca.crt").toFile())
            .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
            .build();
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new MerchantConfigurationGrpcService(merchant), interceptor))
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
    var billingChannel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("billing-service.crt").toFile(),
                        certs.resolve("billing-service.key").toFile())
                    .build())
            .build();
    try {
      var sender =
          new RpcContextSecurity("api-gateway", Clock.systemUTC(), keys, replay, authority);
      var plain =
          ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
      var request =
          request("1", CommercialFixtures.profile()).toBuilder()
              .setContext(wire("publishMerchantConfiguration", true))
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> plain.publishMerchantConfiguration(request))
              .getStatus()
              .getCode());
      var signed =
          plain.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  TenantRegistryIntegrationTest.signed(
                      sender,
                      request,
                      new RpcContextSecurity.Policy(false, true),
                      ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc
                          .getPublishMerchantConfigurationMethod()
                          .getFullMethodName())));
      assertEquals("2", signed.publishMerchantConfiguration(request).getVersion());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> signed.publishMerchantConfiguration(request))
              .getStatus()
              .getCode());
      var reader =
          new RpcContextSecurity("billing-service", Clock.systemUTC(), keys, replay, authority);
      var now = Instant.now();
      var context =
          RequestContext.newBuilder()
              .setHeader(
                  ru.taska.api.common.v1.Header.newBuilder()
                      .setRequestId(UUID.randomUUID().toString()))
              .setContext(
                  TrustedTenantContext.newBuilder()
                      .setContextVersion(1)
                      .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                      .setActorGeneration("service-v1")
                      .setIssuerService("billing-service")
                      .setAudienceService("tenant-service")
                      .setAction("resolveMerchantConfiguration")
                      .addScopes("read")
                      .addScopes("commercial:resolve")
                      .setIssuedAt(now.toString())
                      .setExpiresAt(now.plusSeconds(30).toString())
                      .setAssertionId(UUID.randomUUID().toString()))
              .build();
      var read = ResolveMerchantConfigurationRequest.newBuilder().setContext(context).build();
      var privateStub =
          ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc.newBlockingStub(billingChannel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(
                          reader,
                          read,
                          new RpcContextSecurity.Policy(false, false),
                          ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc
                              .getResolveMerchantConfigurationMethod()
                              .getFullMethodName())));
      assertEquals(
          CommercialFixtures.profile(),
          privateStub.resolveMerchantConfiguration(read).getConfiguration());
      var wrongAudience =
          read.toBuilder()
              .setContext(
                  context.toBuilder()
                      .setContext(
                          context.getContext().toBuilder().setAudienceService("billing-service")))
              .build();
      var wrongAudienceStub =
          ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc.newBlockingStub(billingChannel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(
                          reader,
                          wrongAudience,
                          new RpcContextSecurity.Policy(false, false),
                          ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc
                              .getResolveMerchantConfigurationMethod()
                              .getFullMethodName())));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> wrongAudienceStub.resolveMerchantConfiguration(wrongAudience))
              .getStatus()
              .getCode());
      allowed.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> privateStub.resolveMerchantConfiguration(read))
              .getStatus()
              .getCode());
    } finally {
      channel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      billingChannel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  static RequestContext wire(String action, boolean modify) {
    var original = PlatformFlagsIntegrationTest.wire(modify);
    return original.toBuilder()
        .setContext(original.getContext().toBuilder().setAction(action))
        .build();
  }

  @Test
  void ibanLettersAreExpandedAndInvalidChecksumIsRejected() {
    var c = CommercialFixtures.profile();
    var letters =
        c.toBuilder().setBank(c.getBank().toBuilder().setIban("GB82WEST12345698765432")).build();
    assertTrue(publish(request("1", letters)).block(WAIT).getLiveProfileComplete());
    var invalid =
        letters.toBuilder()
            .setBank(letters.getBank().toBuilder().setIban("GB81WEST12345698765432"))
            .build();
    rejects(DomainStatus.INVALID_ARGUMENT, publish(request("2", invalid)));
    assertEquals("2", current().getVersion());
  }
}
