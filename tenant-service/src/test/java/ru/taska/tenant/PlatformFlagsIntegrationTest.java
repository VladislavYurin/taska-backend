package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.time.ZoneOffset;
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
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListPlatformFlagsRpcRequest;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PageRequest;
import ru.taska.api.b2b.v1.PlatformFlag;
import ru.taska.api.b2b.v1.PlatformFlagPage;
import ru.taska.api.b2b.v1.PlatformServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.b2b.v1.UpdateFlagRequest;
import ru.taska.api.b2b.v1.UpdatePlatformFlagRpcRequest;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextSecurity;

@Testcontainers
@Timeout(90)
class PlatformFlagsIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final Duration WAIT = Duration.ofSeconds(25);
  static final UUID ACTOR = UUID.randomUUID(), SESSION = UUID.randomUUID();
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static ConnectionPool pool;
  static DatabaseClient operator, db;
  static PlatformFlags service;

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
            PlatformFlagsIntegrationTest.class.getResourceAsStream(
                "/db/changelog/db.changelog-master.yaml"));
    var changes = (tools.jackson.databind.node.ArrayNode) log.get("databaseChangeLog");
    changes.remove(changes.size() - 1);
    changes.remove(changes.size() - 1);
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
    assertEquals(
        checksums,
        operator
            .sql(
                "SELECT id,md5sum FROM tenant_liquibase.databasechangelog WHERE id NOT IN"
                    + " ('tenant-platform-flags-0003','tenant-merchant-configuration-0004') ORDER"
                    + " BY id")
            .fetch()
            .all()
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

  static TrustedTenantContext claims(boolean modify) {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setActorGeneration("1")
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
        .setSessionId(SESSION.toString())
        .setAdministrativeScope(AdminScope.newBuilder().setKind(AdminScope.KindValue.KIND_PLATFORM))
        .setAdministrativeLevel(
            modify ? AdminGrant.LevelValue.LEVEL_MODIFY : AdminGrant.LevelValue.LEVEL_READ)
        .addScopes(modify ? "write" : "read")
        .setAction(modify ? "updatePlatformFlag" : "listPlatformFlags")
        .build();
  }

  static <T> Mono<T> admin(boolean modify, Mono<T> work) {
    return RegistryTestContext.withClaims(claims(modify), work);
  }

  static UpdatePlatformFlagRpcRequest request(
      String key, String version, UpdateFlagRequest value, String intent) {
    return UpdatePlatformFlagRpcRequest.newBuilder()
        .setFlagKey(key)
        .setPayload(value)
        .setMutation(
            MutationOptions.newBuilder().setExpectedVersion(version).setIdempotencyKey(intent))
        .build();
  }

  static UpdatePlatformFlagRpcRequest bool(String key, String version, boolean value) {
    return request(
        key,
        version,
        UpdateFlagRequest.newBuilder().setBooleanValue(value).build(),
        UUID.randomUUID().toString());
  }

  static UpdatePlatformFlagRpcRequest duration(String key, String version, String value) {
    return request(
        key,
        version,
        UpdateFlagRequest.newBuilder().setIntegerValue(value).build(),
        UUID.randomUUID().toString());
  }

  static PlatformFlagPage list() {
    return admin(false, service.list(ListPlatformFlagsRpcRequest.getDefaultInstance())).block(WAIT);
  }

  static PlatformFlag flag(String key) {
    return list().getItemsList().stream()
        .filter(f -> f.getKey().equals(key))
        .findFirst()
        .orElseThrow();
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
  void completeAllowlistHasDefaultsTypesOwnersAndNoCoreSwitchesOrSecrets() {
    var flags = list().getItemsList();
    assertEquals(24, flags.size());
    var booleans =
        flags.stream().filter(f -> f.getType() == PlatformFlag.TypeValue.TYPE_BOOLEAN).toList();
    assertEquals(22, booleans.size());
    assertEquals(
        List.of("FREE_MCP", "FREE_PERSONAL_API_TOKENS"),
        booleans.stream().filter(f -> !f.getBooleanValue()).map(PlatformFlag::getKey).toList());
    assertTrue(booleans.stream().allMatch(f -> f.getBooleanValue() == f.getDefaultBooleanValue()));
    assertEquals("2592000", flag("UNPAID_CREATION_WINDOW_SECONDS").getIntegerValue());
    assertEquals("86400", flag("WIRE_RECONCILIATION_TARGET_SECONDS").getIntegerValue());
    assertTrue(
        flags.stream()
            .allMatch(
                f ->
                    !f.getOwner().isBlank()
                        && !f.getEffectiveAt().isBlank()
                        && f.getVersion().equals("1")));
    assertTrue(
        flags.stream()
            .noneMatch(
                f ->
                    f.getKey()
                        .matches(
                            ".*(SECRET|PASSWORD|LOGO|TIME_TRACKING|COMMENT|WATCHER|LABEL|TENANT_CREATION).*")));
  }

  @Test
  void updateIsImmutableAuditedAndEventHasExactVersionEffectiveAtAndValue() {
    var req = bool("MCP", "1", false);
    var updated = admin(true, service.update(req)).block(WAIT);
    assertEquals("2", updated.getVersion());
    assertFalse(updated.getBooleanValue());
    assertTrue(updated.getDefaultBooleanValue());
    assertEquals(updated, flag("MCP"));
    var row =
        operator
            .sql(
                "SELECT changed_by,value::text AS value FROM taska.platform_configuration_versions"
                    + " WHERE key='MCP' AND version=2")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(ACTOR, row.get("changed_by"));
    assertEquals("false", row.get("value"));
    var event =
        operator
            .sql(
                "SELECT aggregate_version,payload->>'version' AS version,payload->>'effectiveAt' AS"
                    + " effective,payload->>'value' AS value FROM taska.global_outbox_events")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(2L, event.get("aggregate_version"));
    assertEquals("2", event.get("version"));
    assertEquals(updated.getEffectiveAt(), event.get("effective"));
    assertEquals("false", event.get("value"));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "UPDATE taska.platform_configuration_versions SET value='true' WHERE key='MCP'"
                        + " AND version=2")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql("DELETE FROM taska.platform_configuration_versions WHERE key='MCP'")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(
        "true",
        operator
            .sql(
                "SELECT value::text value FROM taska.platform_configuration_versions WHERE"
                    + " key='MCP' AND version=1")
            .map((r, m) -> r.get("value", String.class))
            .one()
            .block(WAIT));
  }

  @Test
  void concurrentSameIntentReplaysOnceAndChangedPayloadConflicts() {
    var r = bool("MCP", "1", false);
    var results =
        Mono.zip(
                admin(true, service.update(r)).subscribeOn(Schedulers.parallel()),
                admin(true, service.update(r)).subscribeOn(Schedulers.parallel()))
            .block(WAIT);
    assertEquals(results.getT1(), results.getT2());
    assertEquals(1, count("platform_configuration_receipts"));
    assertEquals(1, count("global_outbox_events"));
    rejects(
        DomainStatus.ALREADY_EXISTS,
        admin(
            true,
            service.update(
                r.toBuilder()
                    .setPayload(UpdateFlagRequest.newBuilder().setBooleanValue(true))
                    .build())));
    admin(true, service.update(bool("MCP", "2", true))).block(WAIT);
    assertEquals(results.getT1(), admin(true, service.update(r)).block(WAIT));
    assertTrue(flag("MCP").getBooleanValue());
  }

  @Test
  void concurrentVersionsNeverLoseAnUpdate() {
    var a =
        admin(true, service.update(bool("EMAIL", "1", false)))
            .materialize()
            .subscribeOn(Schedulers.parallel());
    var b =
        admin(true, service.update(bool("EMAIL", "1", true)))
            .materialize()
            .subscribeOn(Schedulers.parallel());
    var results = Mono.zip(a, b).block(WAIT);
    var list = List.of(results.getT1(), results.getT2());
    assertEquals(1, list.stream().filter(s -> s.isOnNext()).count());
    assertEquals(
        DomainStatus.ABORTED,
        ((DomainException)
                list.stream().filter(s -> s.isOnError()).findFirst().orElseThrow().getThrowable())
            .getStatus());
    assertEquals("2", flag("EMAIL").getVersion());
    assertEquals(1, count("global_outbox_events"));
  }

  @Test
  void validationFailuresNeverWriteHistoryReceiptsOrEvents() {
    for (var r :
        List.of(
            duration("MCP", "1", "5"),
            bool("UNPAID_CREATION_WINDOW_SECONDS", "1", true),
            duration("UNPAID_CREATION_WINDOW_SECONDS", "1", "0"),
            duration("UNPAID_CREATION_WINDOW_SECONDS", "1", "01"),
            duration("WIRE_RECONCILIATION_TARGET_SECONDS", "1", "31536001"),
            duration("WIRE_RECONCILIATION_TARGET_SECONDS", "1", "9223372036854775808"),
            bool("MCP", "0", false),
            bool("MCP", "01", false),
            bool("MCP", "1", false).toBuilder().clearPayload().build(),
            bool("MCP", "1", false).toBuilder()
                .setPayload(
                    UpdateFlagRequest.newBuilder().setBooleanValue(false).setIntegerValue("1"))
                .build())) rejects(DomainStatus.INVALID_ARGUMENT, admin(true, service.update(r)));
    rejects(DomainStatus.NOT_FOUND, admin(true, service.update(bool("SECRET_TOKEN", "1", false))));
    assertEquals(25, count("platform_configuration_versions"));
    assertEquals(0, count("platform_configuration_receipts"));
    assertEquals(0, count("global_outbox_events"));
  }

  @Test
  void tenantAdminUserCredentialsServiceAndReadOnlyAdminCannotMutateOrOverrideTenant() {
    var r = bool("MCP", "1", false);
    for (var c :
        List.of(
            claims(true).toBuilder().setTenantId(UUID.randomUUID().toString()).build(),
            claims(true).toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .build(),
            claims(true).toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
                .build(),
            claims(true).toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                .build(),
            claims(true).toBuilder()
                .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
                .build(),
            claims(true).toBuilder().clearAdministrativeScope().build(),
            claims(true).toBuilder().clearActorGeneration().build(),
            claims(true).toBuilder()
                .setAdministrativeScope(
                    AdminScope.newBuilder()
                        .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                        .addTenantIds(UUID.randomUUID().toString()))
                .build()))
      rejects(DomainStatus.PERMISSION_DENIED, RegistryTestContext.withClaims(c, service.update(r)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        RegistryTestContext.withClaims(
            claims(false).toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .build(),
            service.list(ListPlatformFlagsRpcRequest.getDefaultInstance())));
    rejects(DomainStatus.UNAUTHENTICATED, service.update(r));
    assertEquals(25, count("platform_configuration_versions"));
  }

  @Test
  void administrativeIntegrationRequiresCredentialGrantAndGeneration() {
    var integration =
        claims(true).toBuilder()
            .clearSessionId()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
            .setCredentialId(UUID.randomUUID().toString())
            .setGrantId(UUID.randomUUID().toString())
            .setGrantGeneration("1")
            .build();
    var r = bool("MCP", "1", false);
    for (var c :
        List.of(
            integration.toBuilder().clearGrantId().build(),
            integration.toBuilder().clearGrantGeneration().build(),
            integration.toBuilder().clearCredentialId().build()))
      rejects(DomainStatus.PERMISSION_DENIED, RegistryTestContext.withClaims(c, service.update(r)));
    assertEquals(
        "2",
        RegistryTestContext.withClaims(integration, service.update(r)).block(WAIT).getVersion());
    var read =
        integration.toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
            .setAction("listPlatformFlags")
            .clearScopes()
            .addScopes("read")
            .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
            .build();
    assertEquals(
        24,
        RegistryTestContext.withClaims(
                read, service.list(ListPlatformFlagsRpcRequest.getDefaultInstance()))
            .block(WAIT)
            .getItemsCount());
  }

  @Test
  void cursorExpiryAndMissingKeyFailClosedWithoutBlockingCompleteReads() {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    Instant now = Instant.now();
    String token =
        new PlatformFlagCursor(key, Clock.fixed(now, ZoneOffset.UTC)).encode("binding", "MCP");
    assertThrows(
        DomainException.class,
        () ->
            new PlatformFlagCursor(key, Clock.fixed(now.plusSeconds(900), ZoneOffset.UTC))
                .decode(token, "binding"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlatformFlagCursor("not-base64", Clock.systemUTC()));
    var unconfigured =
        new PlatformFlags(
            db,
            TransactionalOperator.create(new R2dbcTransactionManager(pool)),
            new PlatformFlagCursor("", Clock.systemUTC()));
    assertEquals(
        24,
        admin(false, unconfigured.list(ListPlatformFlagsRpcRequest.getDefaultInstance()))
            .block(WAIT)
            .getItemsCount());
    rejects(
        DomainStatus.UNAVAILABLE,
        admin(
            false,
            unconfigured.list(
                ListPlatformFlagsRpcRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setLimit(1))
                    .build())));
  }

  @Test
  void outboxFailureRollsBackVersionAuditAndReceipt() {
    sql(
        "ALTER TABLE taska.global_outbox_events ADD CONSTRAINT flags_test_failure CHECK"
            + " (event_type<>'PlatformConfigurationChanged')");
    try {
      assertThrows(
          Exception.class, () -> admin(true, service.update(bool("MCP", "1", false))).block(WAIT));
    } finally {
      sql("ALTER TABLE taska.global_outbox_events DROP CONSTRAINT flags_test_failure");
    }
    assertEquals("1", flag("MCP").getVersion());
    assertEquals(0, count("platform_configuration_receipts"));
    assertEquals(25, count("platform_configuration_versions"));
  }

  @Test
  void databaseAlsoRejectsWrongTypesUnknownKeysAndDefinitionEdits() {
    for (String insert :
        List.of(
            "('MCP',2,'BOOLEAN','1',now(),'" + ACTOR + "',now())",
            "('MCP',2,'DURATION_SECONDS','1',now(),'" + ACTOR + "',now())",
            "('UNKNOWN',2,'BOOLEAN','true',now(),'" + ACTOR + "',now())",
            "('WIRE_RECONCILIATION_TARGET_SECONDS',2,'DURATION_SECONDS','0',now(),'"
                + ACTOR
                + "',now())"))
      assertThrows(
          Exception.class,
          () ->
              db.sql("INSERT INTO taska.platform_configuration_versions VALUES " + insert)
                  .fetch()
                  .rowsUpdated()
                  .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "UPDATE taska.platform_configuration_definitions SET default_value='false'"
                        + " WHERE key='MCP'")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(
        "1000",
        db.sql("SELECT '1e3'::jsonb::text AS n")
            .map((row, m) -> row.get("n", String.class))
            .one()
            .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "INSERT INTO taska.platform_configuration_versions"
                        + " VALUES('WIRE_RECONCILIATION_TARGET_SECONDS',2,'DURATION_SECONDS','1.0',now(),:actor,now())")
                .bind("actor", ACTOR)
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(25, count("platform_configuration_versions"));
  }

  @Test
  void cursorBindsActorSessionPageSizeGenerationAndIgnoresPerRequestAssertionTimes() {
    var r =
        ListPlatformFlagsRpcRequest.newBuilder()
            .setPage(PageRequest.newBuilder().setLimit(2))
            .build();
    var pageClaims = claims(false).toBuilder().addScopes("configuration").build();
    var first = RegistryTestContext.withClaims(pageClaims, service.list(r)).block(WAIT);
    assertEquals(2, first.getItemsCount());
    assertTrue(first.hasNextCursor());
    var next =
        r.toBuilder().setPage(r.getPage().toBuilder().setCursor(first.getNextCursor())).build();
    var second =
        RegistryTestContext.withClaims(
                pageClaims.toBuilder()
                    .clearScopes()
                    .addScopes("configuration")
                    .addScopes("read")
                    .setIssuedAt(Instant.now().toString())
                    .setAssertionId(UUID.randomUUID().toString())
                    .build(),
                service.list(next))
            .block(WAIT);
    assertNotEquals(first.getItems(0), second.getItems(0));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.withClaims(
            pageClaims.toBuilder().setSessionId(UUID.randomUUID().toString()).build(),
            service.list(next)));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.withClaims(
            pageClaims,
            service.list(
                next.toBuilder().setPage(next.getPage().toBuilder().setLimit(3)).build())));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.withClaims(
            pageClaims,
            service.list(
                next.toBuilder()
                    .setPage(next.getPage().toBuilder().setCursor(first.getNextCursor() + "x"))
                    .build())));
    admin(true, service.update(bool("MCP", "1", false))).block(WAIT);
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.withClaims(pageClaims, service.list(next)));
  }

  @Test
  void changedWindowAffectsOnlyNewCreationsAndRetriesKeepOriginalDeadline() {
    var transactions = new TenantTransactionExecutor(pool);
    var registry =
        new TenantRegistryService(
            transactions,
            id -> Mono.just(new RegistryDependencies.PublishedPlan(id, false)),
            (connection, actor, tenant) -> Mono.empty(),
            Clock.systemUTC(),
            connection -> PlatformFlags.duration(connection, "UNPAID_CREATION_WINDOW_SECONDS"));
    var create =
        TenantRegistryIntegrationTest.request(
            UUID.randomUUID().toString(), UUID.randomUUID(), "Deadline test");
    var first =
        RegistryTestContext.as(
                ACTOR,
                null,
                "createTenant",
                CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                true,
                registry.create(create))
            .block(WAIT);
    assertEquals(
        Duration.ofDays(30),
        Duration.between(
            Instant.parse(first.getTenant().getCreatedAt()),
            Instant.parse(first.getTenant().getCreationDeadline())));
    admin(true, service.update(duration("UNPAID_CREATION_WINDOW_SECONDS", "1", "86400")))
        .block(WAIT);
    var replay =
        RegistryTestContext.as(
                ACTOR,
                null,
                "createTenant",
                CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                true,
                registry.create(create))
            .block(WAIT);
    assertEquals(first, replay);
    var another =
        create.toBuilder()
            .setMutation(
                MutationOptions.newBuilder().setIdempotencyKey(UUID.randomUUID().toString()))
            .build();
    var second =
        RegistryTestContext.as(
                ACTOR,
                null,
                "createTenant",
                CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                true,
                registry.create(another))
            .block(WAIT);
    assertEquals(
        Duration.ofDays(1),
        Duration.between(
            Instant.parse(second.getTenant().getCreatedAt()),
            Instant.parse(second.getTenant().getCreationDeadline())));
  }

  @Test
  void realMtlsPoliciesBindPayloadAndRecheckCurrentAdministrativeAuthority(@TempDir Path certs)
      throws Exception {
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
    for (String name : List.of("api-gateway", "tenant-service")) {
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
            return issuer.equals("api-gateway") && kid.equals("test") ? pair.getPublic() : null;
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
                io.grpc.ServerInterceptors.intercept(new PlatformGrpcService(service), interceptor))
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
      var sender =
          new RpcContextSecurity("api-gateway", Clock.systemUTC(), keys, replay, authority);
      var plain =
          PlatformServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
      var read = ListPlatformFlagsRpcRequest.newBuilder().setContext(wire(false)).build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.listPlatformFlags(read))
              .getStatus()
              .getCode());
      var readStub =
          plain.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  TenantRegistryIntegrationTest.signed(
                      sender,
                      read,
                      new RpcContextSecurity.Policy(false, false),
                      PlatformServiceGrpc.getListPlatformFlagsMethod().getFullMethodName())));
      assertEquals(24, readStub.listPlatformFlags(read).getItemsCount());
      var change = bool("FREE_MCP", "1", true).toBuilder().setContext(wire(true)).build();
      var writeStub =
          plain.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  TenantRegistryIntegrationTest.signed(
                      sender,
                      change,
                      new RpcContextSecurity.Policy(false, true),
                      PlatformServiceGrpc.getUpdatePlatformFlagMethod().getFullMethodName())));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> writeStub.updatePlatformFlag(change.toBuilder().setFlagKey("MCP").build()))
              .getStatus()
              .getCode());
      var result = writeStub.updatePlatformFlag(change);
      assertTrue(result.getBooleanValue());
      assertEquals("2", result.getVersion());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class, () -> writeStub.updatePlatformFlag(change))
              .getStatus()
              .getCode());
      allowed.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> readStub.listPlatformFlags(read))
              .getStatus()
              .getCode());
      allowed.set(true);
      var retry = change.toBuilder().setContext(wire(true)).build();
      var retryStub =
          plain.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  TenantRegistryIntegrationTest.signed(
                      sender,
                      retry,
                      new RpcContextSecurity.Policy(false, true),
                      PlatformServiceGrpc.getUpdatePlatformFlagMethod().getFullMethodName())));
      allowed.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class, () -> retryStub.updatePlatformFlag(retry))
              .getStatus()
              .getCode());
      assertEquals("2", flag("FREE_MCP").getVersion());
    } finally {
      channel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  static RequestContext wire(boolean modify) {
    Instant now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            claims(modify).toBuilder()
                .setContextVersion(1)
                .setIssuerService("api-gateway")
                .setAudienceService("tenant-service")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }
}
