package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.quota.ProjectQuotaProtocol.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.projectstorage.ProjectQuotaOwner;
import ru.taska.projectstorage.ProjectQuotaOwnerGrpcService;
import ru.taska.projectstorage.TenantProjectStore;
import ru.taska.quota.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/** Production owner + root on separate PostgreSQL app roles, connected through actual mTLS/JWS. */
@Testcontainers
@Timeout(90)
class ProjectQuotaIntegrationTest {
  @Container static final PostgreSQLContainer<?> rootPg = new PostgreSQLContainer<>("postgres:16");
  @Container static final PostgreSQLContainer<?> ownerPg = new PostgreSQLContainer<>("postgres:16");
  @TempDir static Path certs;
  static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), ACTOR = UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(25);
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static ConnectionPool rootPool, ownerPool;
  static DatabaseClient rootAdmin, ownerAdmin;
  static TenantTransactionExecutor rootTx, ownerTx;
  static Server rootServer, ownerServer;
  static ManagedChannel rootChannel, ownerChannel;
  static GrpcProjectQuotaAuthority authorityClient;
  static ProjectQuotaStore root;
  static ProjectQuotaOwner owner;
  static final AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
  static final Clock clock =
      new Clock() {
        public Instant instant() {
          return time.get();
        }

        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
          return this;
        }
      };
  static final AtomicBoolean enabled = new AtomicBoolean(true),
      permitted = new AtomicBoolean(true),
      bypass = new AtomicBoolean(),
      ownerOnline = new AtomicBoolean(true),
      billingOnline = new AtomicBoolean(true),
      failOpen = new AtomicBoolean();
  static final AtomicBoolean loseCancelResponse = new AtomicBoolean();
  static final AtomicBoolean expireOnInspect = new AtomicBoolean();
  static final AtomicReference<CredentialKind> lastCredential = new AtomicReference<>();
  static final AtomicLong limit = new AtomicLong(1),
      entitlement = new AtomicLong(1),
      policyVersion = new AtomicLong(1);

  static ConnectionFactory factory(PostgreSQLContainer<?> pg, String user, String password) {
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

  static void bootstrap(PostgreSQLContainer<?> pg, String service) throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    var result =
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
            "service=" + service,
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, result.getExitCode(), result.getStderr());
    var admin = DatabaseClient.create(factory(pg, pg.getUsername(), pg.getPassword()));
    admin.sql("CREATE EXTENSION IF NOT EXISTS pgcrypto").then().block(WAIT);
    admin
        .sql("CREATE SCHEMA " + service + "_liquibase AUTHORIZATION taska_" + service + "_migrator")
        .then()
        .block(WAIT);
    admin.sql("REVOKE ALL ON SCHEMA " + service + "_liquibase FROM PUBLIC").then().block(WAIT);
    admin.sql("ALTER ROLE taska_" + service + "_app PASSWORD '" + APP + "'").then().block(WAIT);
    admin
        .sql("ALTER ROLE taska_" + service + "_deployer PASSWORD '" + DEPLOY + "'")
        .then()
        .block(WAIT);
  }

  @BeforeAll
  static void setup() throws Exception {
    bootstrap(rootPg, "tenant");
    bootstrap(ownerPg, "project");
    rootAdmin = DatabaseClient.create(factory(rootPg, rootPg.getUsername(), rootPg.getPassword()));
    ownerAdmin =
        DatabaseClient.create(factory(ownerPg, ownerPg.getUsername(), ownerPg.getPassword()));
    var rootMigration =
        new TenantConfiguration()
            .registryLiquibase(rootPg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
    rootMigration.afterPropertiesSet();
    rootMigration.afterPropertiesSet();
    var ownerMigration =
        new ru.taska.projectbootstrap.TenantStorageConfiguration()
            .projectTenantLiquibase(ownerPg.getJdbcUrl(), "taska_project_deployer", DEPLOY);
    ownerMigration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
    ownerMigration.afterPropertiesSet();
    ownerMigration.afterPropertiesSet();
    rootPool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory(rootPg, "taska_tenant_app", APP))
                .maxSize(4)
                .build());
    ownerPool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory(ownerPg, "taska_project_app", APP))
                .maxSize(4)
                .build());
    rootTx = new TenantTransactionExecutor(rootPool);
    ownerTx = new TenantTransactionExecutor(ownerPool);
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
        "/CN=project-quota-test");
    for (String name : List.of("tenant-service", "project-service")) {
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
    var pairs =
        Map.of(
            "tenant-service",
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            "project-service",
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("fixture", pairs.get(issuer).getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return pairs.containsKey(issuer) && "fixture".equals(kid)
                ? pairs.get(issuer).getPublic()
                : null;
          }
        };
    var assertions = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.fromSupplier(() -> assertions.add(issuer + ":" + jti));
    RpcContextSecurity.Authority trust =
        (c, rpc, p) ->
            Mono.just(
                Set.of(
                            CredentialKind.CREDENTIAL_KIND_SERVICE,
                            CredentialKind.CREDENTIAL_KIND_WEB_TENANT,
                            CredentialKind.CREDENTIAL_KIND_USER_PAT)
                        .contains(c.getContext().getCredentialKind())
                    && Set.of("tenant-service", "project-service")
                        .contains(c.getContext().getIssuerService()));
    var rootSecurity =
        new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, trust);
    var ownerSecurity =
        new RpcContextSecurity("project-service", Clock.systemUTC(), keys, replay, trust);
    var authorityRef = new AtomicReference<ProjectQuotaProtocol.Authority>();
    ProjectQuotaProtocol.Authority deferred =
        new ProjectQuotaProtocol.Authority() {
          public Mono<Reservation> reserve(UUID op, UUID id, Instant exp) {
            return Mono.defer(() -> authorityRef.get().reserve(op, id, exp));
          }

          public Mono<Reservation> get(UUID op) {
            return Mono.defer(() -> authorityRef.get().get(op));
          }

          public Mono<Reservation> commit(Reservation r) {
            return Mono.defer(() -> authorityRef.get().commit(r));
          }

          public Mono<Reservation> release(Reservation r) {
            return Mono.defer(() -> authorityRef.get().release(r));
          }
        };
    owner =
        new ProjectQuotaOwner(
            ownerTx,
            deferred,
            (c, use, op, project) -> {
              if (!ownerOnline.get()
                  || !permitted.get()
                  || (use == ProjectQuotaOwner.Use.OPEN && failOpen.get()))
                return Mono.error(
                    new DomainException(DomainStatus.UNAVAILABLE, "owner unavailable"));
              if (use == ProjectQuotaOwner.Use.INSPECT && expireOnInspect.get())
                time.set(time.get().plusSeconds(61));
              return Mono.empty();
            },
            clock);
    var ownerPolicy = new HashMap<String, RpcContextSecurity.Policy>();
    for (String method :
        List.of(
            ProjectQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            ProjectQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            ProjectQuotaOwnerServiceGrpc.getOpenMethod().getFullMethodName(),
            ProjectQuotaOwnerServiceGrpc.getInventoryMethod().getFullMethodName()))
      ownerPolicy.put(method, new RpcContextSecurity.Policy(true, false));
    ownerServer =
        server("project-service")
            .addService(
                ServerInterceptors.intercept(
                    new ProjectQuotaOwnerGrpcService(owner),
                    new TenantServerInterceptor(ownerSecurity, ownerPolicy, "test"),
                    new ServerInterceptor() {
                      public <Q, S> ServerCall.Listener<Q> interceptCall(
                          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
                        boolean fault =
                            call.getMethodDescriptor()
                                    .getFullMethodName()
                                    .equals(
                                        ProjectQuotaOwnerServiceGrpc.getCancelMethod()
                                            .getFullMethodName())
                                && loseCancelResponse.get();
                        if (!fault) return next.startCall(call, headers);
                        return next.startCall(
                            new ForwardingServerCall.SimpleForwardingServerCall<Q, S>(call) {
                              @Override
                              public void sendMessage(S message) {}

                              @Override
                              public void close(Status status, Metadata trailers) {
                                super.close(
                                    Status.UNAVAILABLE.withDescription(
                                        "fixture lost cancellation acknowledgement"),
                                    new Metadata());
                              }
                            },
                            headers);
                      }
                    }))
            .build()
            .start();
    ownerChannel = channel("tenant-service", ownerServer.getPort());
    var ownerClient =
        new GrpcProjectQuotaOwner(
            ReactorProjectQuotaOwnerServiceGrpc.newReactorStub(ownerChannel),
            rootSecurity,
            Clock.systemUTC(),
            "tenant-service");
    root =
        new ProjectQuotaStore(
            rootTx,
            (c, command, op) -> {
              lastCredential.set(RpcContextBridge.details(c).getContext().getCredentialKind());
              if (!permitted.get())
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "current policy revoked"));
              if (command == ProjectQuotaStore.Command.RESERVE_NEW
                  && !enabled.get()
                  && !bypass.get())
                return Mono.error(
                    new DomainException(DomainStatus.FAILED_PRECONDITION, "CAPABILITY_DISABLED"));
              return Mono.just(
                  new ProjectQuotaStore.Grant(
                      bypass.get(), policyVersion.get(), Duration.ofMinutes(5)));
            },
            ownerClient,
            (tenant, min) ->
                billingOnline.get()
                    ? Mono.just(terms(tenant))
                    : Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "billing offline")),
            clock);
    var rootPolicies = new HashMap<String, RpcContextSecurity.Policy>();
    for (String method :
        List.of(
            QuotaAuthorityServiceGrpc.getReserveQuotaMethod().getFullMethodName(),
            QuotaAuthorityServiceGrpc.getCommitQuotaMethod().getFullMethodName(),
            QuotaAuthorityServiceGrpc.getReleaseQuotaMethod().getFullMethodName(),
            QuotaAuthorityServiceGrpc.getGetQuotaReservationMethod().getFullMethodName()))
      rootPolicies.put(method, new RpcContextSecurity.Policy(true, false));
    rootServer =
        server("tenant-service")
            .addService(
                ServerInterceptors.intercept(
                    new ProjectQuotaGrpcService(root),
                    new TenantServerInterceptor(rootSecurity, rootPolicies, "test")))
            .build()
            .start();
    rootChannel = channel("project-service", rootServer.getPort());
    authorityClient =
        new GrpcProjectQuotaAuthority(
            ReactorQuotaAuthorityServiceGrpc.newReactorStub(rootChannel),
            ownerSecurity,
            Clock.systemUTC(),
            "project-service");
    authorityRef.set(authorityClient);
  }

  static NettyServerBuilder server(String identity) throws Exception {
    return NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certs.resolve(identity + ".crt").toFile(),
                    certs.resolve(identity + ".key").toFile())
                .trustManager(certs.resolve("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build());
  }

  static ManagedChannel channel(String identity, int port) throws Exception {
    return NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(certs.resolve("ca.crt").toFile())
                .keyManager(
                    certs.resolve(identity + ".crt").toFile(),
                    certs.resolve(identity + ".key").toFile())
                .build())
        .build();
  }

  @AfterAll
  static void close() throws Exception {
    if (rootChannel != null) rootChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    if (ownerChannel != null) ownerChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    if (rootServer != null) rootServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    if (ownerServer != null) ownerServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    if (rootPool != null) rootPool.disposeLater().block(WAIT);
    if (ownerPool != null) ownerPool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void seed() {
    loseCancelResponse.set(false);
    expireOnInspect.set(false);
    time.set(Instant.now());
    enabled.set(true);
    permitted.set(true);
    bypass.set(false);
    ownerOnline.set(true);
    billingOnline.set(true);
    failOpen.set(false);
    limit.set(1);
    entitlement.set(1);
    policyVersion.set(1);
    rootAdmin
        .sql(
            "TRUNCATE"
                + " taska.quota_effects,taska.quota_reservations,taska.quota_counters,taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events")
        .then()
        .block(WAIT);
    ownerAdmin
        .sql(
            "TRUNCATE"
                + " taska.projects,taska.project_members,taska.project_settings,taska.project_issue_types,taska.project_type_operations,taska.issue_type_creations,taska.issue_type_deletions,taska.outbox_events,taska.resource_operation_receipts"
                + " CASCADE")
        .then()
        .block(WAIT);
    try (var connection =
        java.sql.DriverManager.getConnection(
            rootPg.getJdbcUrl(), rootPg.getUsername(), rootPg.getPassword())) {
      connection.setAutoCommit(false);
      for (UUID tenant : List.of(A, B)) {
        try (var statement =
            connection.prepareStatement(
                "INSERT INTO"
                    + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                    + " VALUES(?,'fixture',?,'ACTIVE','FREE',?,?,decode(repeat('00',32),'hex'),?)")) {
          statement.setObject(1, tenant);
          statement.setObject(2, ACTOR);
          statement.setObject(3, UUID.randomUUID());
          statement.setObject(4, ACTOR);
          statement.setObject(5, UUID.randomUUID());
          statement.executeUpdate();
        }
        try (var statement =
            connection.prepareStatement(
                "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                    + " VALUES(?,?,'TENANT_ADMIN','ACTIVE')")) {
          statement.setObject(1, tenant);
          statement.setObject(2, ACTOR);
          statement.executeUpdate();
        }
      }
      connection.commit();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Cannot initialize owned fixture", e);
    }
  }

  static Entitlement terms(UUID tenant) {
    var e =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(Long.toString(entitlement.get()))
            .setPlanVersionId("30000000-0000-0000-0000-000000000001")
            .setValidFrom(time.get().minusSeconds(1).toString())
            .setIssuedAt(time.get().toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      e.addLimits(
          QuotaLimit.newBuilder()
              .setKind(kind)
              .setLimit(
                  Long.toString(kind == QuotaLimit.KindValue.KIND_PROJECT ? limit.get() : 50)));
    return e.build();
  }

  static <T> Mono<T> as(UUID tenant, Mono<T> work) {
    return RegistryTestContext.withClaims(
        TrustedTenantContext.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setTenantId(tenant.toString())
            .setAction("projectQuota")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .setSessionId(UUID.randomUUID().toString())
            .setActorGeneration("3")
            .setMembershipGeneration("5")
            .addScopes("read")
            .addScopes("write")
            .build(),
        work);
  }

  static Reservation reserve(UUID tenant, UUID operation, UUID resource) {
    return as(tenant, authorityClient.reserve(operation, resource, time.get().plusSeconds(60)))
        .block(WAIT);
  }

  static ProjectQuotaOwner.Create command(String key) {
    return new ProjectQuotaOwner.Create(key, "Fixture", ACTOR);
  }

  static QuotaUsage usage(UUID tenant) {
    return as(tenant, root.current(tenant)).block(WAIT);
  }

  static long rows(DatabaseClient db, String table) {
    return db.sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void lastSlotAndTerminalRetriesDoNotDoubleCount() {
    var attempts =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(
                            A,
                            authorityClient.reserve(
                                UUID.randomUUID(), UUID.randomUUID(), time.get().plusSeconds(60)))
                        .materialize(),
                2)
            .collectList()
            .block(WAIT);
    assertEquals(1, attempts.stream().filter(Signal::isOnNext).count());
    assertEquals("1", usage(A).getReserved());
    var r = attempts.stream().filter(Signal::isOnNext).findFirst().orElseThrow().get();
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    assertThrows(
        Exception.class,
        () ->
            as(
                    A,
                    ownerTx.execute(
                        db ->
                            db.sql(
                                    "UPDATE taska.projects SET admission_state='OPEN' WHERE"
                                        + " tenant_id=:t AND id=:id")
                                .bind("t", A)
                                .bind("id", r.resource())
                                .then()))
                .block(WAIT));
    assertTrue(
        as(
                A,
                new TenantProjectStore(ownerTx, new tools.jackson.databind.ObjectMapper())
                    .execute(s -> s.find(r.resource())))
            .blockOptional(WAIT)
            .isEmpty());
    assertEquals(State.COMMITTED, as(A, authorityClient.commit(r)).block(WAIT).state());
    assertEquals(State.COMMITTED, as(A, authorityClient.commit(r)).block(WAIT).state());
    assertEquals("1", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
    assertEquals(1, rows(rootAdmin, "quota_effects"));
    assertEquals(1, rows(ownerAdmin, "projects"));
    assertEquals(2, rows(ownerAdmin, "outbox_events"));
  }

  @Test
  void prePersistenceExpirySealsAbsenceAndRejectsLateCreate() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    time.set(r.expiresAt());
    assertEquals(State.EXPIRED, as(A, authorityClient.release(r)).block(WAIT).state());
    assertEquals(State.EXPIRED, as(A, authorityClient.release(r)).block(WAIT).state());
    assertThrows(Exception.class, () -> as(A, owner.provision(r, command("AA"))).block(WAIT));
    assertThrows(Exception.class, () -> as(A, authorityClient.commit(r)).block(WAIT));
    assertEquals("0", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
    assertEquals(0, rows(ownerAdmin, "projects"));
    assertEquals(1, rows(ownerAdmin, "resource_operation_receipts"));
  }

  @Test
  void outageNeverFreesUnknownReservationAndReconciliationCancelsHiddenResource() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    time.set(r.expiresAt());
    ownerOnline.set(false);
    assertThrows(Exception.class, () -> as(A, root.reconcile()).block(WAIT));
    assertEquals(
        1L,
        rootAdmin
            .sql(
                "SELECT reserved FROM taska.quota_counters WHERE tenant_id=:t AND"
                    + " resource='PROJECT'")
            .bind("t", A)
            .map((row, m) -> row.get("reserved", Long.class))
            .one()
            .block(WAIT));
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    ownerOnline.set(true);
    as(A, root.reconcile()).block(WAIT);
    assertEquals("0", usage(A).getReserved());
    assertEquals(0, rows(ownerAdmin, "projects"));
    assertEquals(State.EXPIRED, as(A, authorityClient.get(r.operation())).block(WAIT).state());
  }

  @Test
  void crashAfterRootCommitRecoversSameProjectAndAcceptedFlagOffRetry() {
    var op = UUID.randomUUID();
    var id = UUID.randomUUID();
    var r = reserve(A, op, id);
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    enabled.set(false);
    policyVersion.incrementAndGet();
    failOpen.set(true);
    assertThrows(Exception.class, () -> as(A, authorityClient.commit(r)).block(WAIT));
    assertEquals(State.COMMITTED, as(A, authorityClient.get(op)).block(WAIT).state());
    assertEquals("1", usage(A).getUsed());
    assertEquals(r.id(), as(A, authorityClient.reserve(op, id, r.expiresAt())).block(WAIT).id());
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    failOpen.set(false);
    time.set(r.expiresAt().plusSeconds(1));
    as(A, root.reconcile()).block(WAIT);
    as(A, authorityClient.commit(r)).block(WAIT);
    assertEquals(OwnerState.OPEN, as(A, owner.inspect(r)).block(WAIT).state());
    assertEquals(1, rows(ownerAdmin, "projects"));
    assertEquals(2, rows(ownerAdmin, "outbox_events"));
  }

  @Test
  void archiveKeepsUsageAndOnlyPhysicalDeletionReleasesItOnce() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    as(A, authorityClient.commit(r)).block(WAIT);
    ownerAdmin.sql("UPDATE taska.projects SET archived_at=now()").then().block(WAIT);
    assertEquals("1", usage(A).getUsed());
    assertThrows(Exception.class, () -> as(A, root.releaseUsage(r)).block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            as(
                    A,
                    ownerTx.execute(
                        db ->
                            db.sql("DELETE FROM taska.projects WHERE tenant_id=:t AND id=:id")
                                .bind("t", A)
                                .bind("id", r.resource())
                                .then()))
                .block(WAIT));
    // Future TAS-459 physical cleanup is modeled by the operator of this owned fixture DB.
    // App DELETE remains forbidden; no production guard is disabled or replaced in this test.
    ownerAdmin
        .sql(
            "TRUNCATE"
                + " taska.projects,taska.project_members,taska.project_settings,taska.project_issue_types,taska.project_type_operations,taska.issue_type_creations,taska.issue_type_deletions"
                + " CASCADE")
        .then()
        .block(WAIT);
    as(A, ownerTx.execute(db -> owner.recordDeleted(db, r))).block(WAIT);
    as(A, root.releaseUsage(r)).block(WAIT);
    as(A, root.releaseUsage(r)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
    rootAdmin
        .sql(
            "UPDATE taska.quota_reservations SET project_opened_at=NULL WHERE tenant_id=:t AND"
                + " operation_id=:op AND resource='PROJECT'")
        .bind("t", A)
        .bind("op", r.operation())
        .then()
        .block(WAIT);
    as(A, root.reconcile()).block(WAIT);
    assertEquals(State.COMMITTED, as(A, authorityClient.commit(r)).block(WAIT).state());
    assertEquals(0, rows(ownerAdmin, "projects"));
    assertEquals("0", usage(A).getUsed());
    assertEquals(2, rows(rootAdmin, "quota_effects"));
    assertEquals(1, rows(ownerAdmin, "resource_operation_receipts"));
    reserve(A, UUID.randomUUID(), UUID.randomUUID());
    assertEquals("1", usage(A).getReserved());
  }

  @Test
  void scopedBypassCountsRealUsageAndPreservesOtherTenant() {
    var a = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(a, command("AA"))).block(WAIT);
    as(A, authorityClient.commit(a)).block(WAIT);
    bypass.set(true);
    var extra = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(extra, command("BB"))).block(WAIT);
    as(A, authorityClient.commit(extra)).block(WAIT);
    bypass.set(false);
    var proof =
        rootAdmin
            .sql(
                "SELECT project_actor_id,project_credential_kind,project_bypassed_limit FROM"
                    + " taska.quota_reservations WHERE tenant_id=:t AND operation_id=:op")
            .bind("t", A)
            .bind("op", extra.operation())
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(ACTOR, proof.get("project_actor_id"));
    assertEquals("CREDENTIAL_KIND_WEB_TENANT", proof.get("project_credential_kind"));
    assertEquals(true, proof.get("project_bypassed_limit"));
    assertThrows(
        Exception.class,
        () ->
            as(
                    A,
                    rootTx.execute(
                        db ->
                            db.sql(
                                    "UPDATE taska.quota_reservations SET"
                                        + " project_bypassed_limit=false WHERE tenant_id=:t AND"
                                        + " operation_id=:op AND resource='PROJECT'")
                                .bind("t", A)
                                .bind("op", extra.operation())
                                .then()))
                .block(WAIT));
    assertEquals("2", usage(A).getUsed());
    assertEquals("0", usage(B).getUsed());
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    assertThrows(Exception.class, () -> as(B, authorityClient.commit(a)).block(WAIT));
    assertThrows(Exception.class, () -> as(B, owner.provision(a, command("CC"))).block(WAIT));
    assertEquals("2", usage(A).getUsed());
  }

  @Test
  void conflictAndFailedOwnerWritePreserveReservationForSafeRetry() {
    var op = UUID.randomUUID();
    var resource = UUID.randomUUID();
    var r = reserve(A, op, resource);
    assertThrows(
        Exception.class,
        () -> as(A, authorityClient.reserve(op, UUID.randomUUID(), r.expiresAt())).block(WAIT));
    ownerAdmin
        .sql(
            "CREATE FUNCTION taska.fail_quota_outbox() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN"
                + " RAISE EXCEPTION 'fixture rollback';END$$")
        .then()
        .block(WAIT);
    ownerAdmin
        .sql(
            "CREATE TRIGGER fail_quota_outbox BEFORE INSERT ON taska.outbox_events FOR EACH ROW"
                + " EXECUTE FUNCTION taska.fail_quota_outbox()")
        .then()
        .block(WAIT);
    try {
      assertThrows(Exception.class, () -> as(A, owner.provision(r, command("AA"))).block(WAIT));
      assertEquals(0, rows(ownerAdmin, "projects"));
      assertEquals(0, rows(ownerAdmin, "resource_operation_receipts"));
      assertEquals(0, rows(ownerAdmin, "outbox_events"));
      assertEquals("1", usage(A).getReserved());
    } finally {
      ownerAdmin.sql("DROP TRIGGER fail_quota_outbox ON taska.outbox_events").then().block(WAIT);
      ownerAdmin.sql("DROP FUNCTION taska.fail_quota_outbox()").then().block(WAIT);
    }
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    assertThrows(
        Exception.class,
        () ->
            as(A, owner.provision(r, new ProjectQuotaOwner.Create("AA", "Changed", ACTOR)))
                .block(WAIT));
    as(A, authorityClient.commit(r)).block(WAIT);
    assertEquals("1", usage(A).getUsed());
  }

  @Test
  void delegatedUserPatKeepsItsCredentialAndCannotGainWriteFromTheService() {
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setTenantId(A.toString())
            .setAction("projectQuota")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
            .setCredentialId(UUID.randomUUID().toString())
            .setActorGeneration("3")
            .setMembershipGeneration("5")
            .addScopes("read");
    assertThrows(
        Exception.class,
        () ->
            RegistryTestContext.withClaims(
                    claims.build(),
                    authorityClient.reserve(
                        UUID.randomUUID(), UUID.randomUUID(), time.get().plusSeconds(60)))
                .block(WAIT));
    assertEquals(0, rows(rootAdmin, "quota_reservations"));
    claims.addScopes("write");
    RegistryTestContext.withClaims(
            claims.build(),
            authorityClient.reserve(
                UUID.randomUUID(), UUID.randomUUID(), time.get().plusSeconds(60)))
        .block(WAIT);
    assertEquals(CredentialKind.CREDENTIAL_KIND_USER_PAT, lastCredential.get());
    assertEquals(1, rows(rootAdmin, "quota_reservations"));
  }

  @Test
  void cancellationFailureAfterDeleteRollsBackProjectMembershipAndReceipt() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    ownerAdmin
        .sql(
            "CREATE FUNCTION taska.fail_cancel() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE"
                + " EXCEPTION 'fixture rollback';END$$")
        .then()
        .block(WAIT);
    ownerAdmin
        .sql(
            "CREATE TRIGGER fail_cancel AFTER DELETE ON taska.projects FOR EACH ROW EXECUTE"
                + " FUNCTION taska.fail_cancel()")
        .then()
        .block(WAIT);
    try {
      assertThrows(Exception.class, () -> as(A, authorityClient.release(r)).block(WAIT));
      assertEquals(1, rows(ownerAdmin, "projects"));
      assertEquals(1, rows(ownerAdmin, "project_members"));
      assertEquals(OwnerState.PROVISIONING, as(A, owner.inspect(r)).block(WAIT).state());
      assertEquals("1", usage(A).getReserved());
      assertEquals(State.ACTIVE, as(A, authorityClient.get(r.operation())).block(WAIT).state());
    } finally {
      ownerAdmin.sql("DROP TRIGGER fail_cancel ON taska.projects").then().block(WAIT);
      ownerAdmin.sql("DROP FUNCTION taska.fail_cancel()").then().block(WAIT);
    }
    assertEquals(State.RELEASED, as(A, authorityClient.release(r)).block(WAIT).state());
    assertEquals(State.RELEASED, as(A, authorityClient.release(r)).block(WAIT).state());
    assertEquals(0, rows(ownerAdmin, "projects"));
    assertEquals(1, rows(ownerAdmin, "resource_operation_receipts"));
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void expiryDuringPrimaryInspectionNeverConsumesUsage() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    expireOnInspect.set(true);
    var error =
        assertThrows(
            io.grpc.StatusRuntimeException.class,
            () -> as(A, authorityClient.commit(r)).block(WAIT));
    assertEquals(io.grpc.Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    assertEquals("RESERVATION_EXPIRED", error.getStatus().getDescription());
    expireOnInspect.set(false);
    assertEquals(0, rows(rootAdmin, "quota_effects"));
    assertEquals(State.ACTIVE, as(A, authorityClient.get(r.operation())).block(WAIT).state());
    assertEquals(OwnerState.PROVISIONING, as(A, owner.inspect(r)).block(WAIT).state());
    as(A, root.reconcile()).block(WAIT);
    assertEquals("0", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void openingOutboxFailureKeepsProjectHiddenAndRecoveryOpensItOnce() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    ownerAdmin
        .sql(
            "CREATE FUNCTION taska.fail_open() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF"
                + " NEW.event_type='ProjectAdmissionOpened' THEN RAISE EXCEPTION 'fixture"
                + " rollback';END IF;RETURN NEW;END$$")
        .then()
        .block(WAIT);
    ownerAdmin
        .sql(
            "CREATE TRIGGER fail_open BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE"
                + " FUNCTION taska.fail_open()")
        .then()
        .block(WAIT);
    try {
      assertThrows(Exception.class, () -> as(A, authorityClient.commit(r)).block(WAIT));
      assertEquals(State.COMMITTED, as(A, authorityClient.get(r.operation())).block(WAIT).state());
      assertEquals(OwnerState.PROVISIONING, as(A, owner.inspect(r)).block(WAIT).state());
      assertEquals(1, rows(ownerAdmin, "outbox_events"));
      assertTrue(
          as(
                  A,
                  new TenantProjectStore(ownerTx, new tools.jackson.databind.ObjectMapper())
                      .execute(s -> s.find(r.resource())))
              .blockOptional(WAIT)
              .isEmpty());
    } finally {
      ownerAdmin.sql("DROP TRIGGER fail_open ON taska.outbox_events").then().block(WAIT);
      ownerAdmin.sql("DROP FUNCTION taska.fail_open()").then().block(WAIT);
    }
    as(A, root.reconcile()).block(WAIT);
    as(A, root.reconcile()).block(WAIT);
    assertEquals(OwnerState.OPEN, as(A, owner.inspect(r)).block(WAIT).state());
    assertEquals(2, rows(ownerAdmin, "outbox_events"));
    assertEquals("1", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void lostCancellationAcknowledgementKeepsDurableIntentAndFencesCommit() {
    var r = reserve(A, UUID.randomUUID(), UUID.randomUUID());
    as(A, owner.provision(r, command("AA"))).block(WAIT);
    loseCancelResponse.set(true);
    assertThrows(Exception.class, () -> as(A, authorityClient.release(r)).block(WAIT));
    var pending = as(A, authorityClient.get(r.operation())).block(WAIT);
    assertEquals(State.ACTIVE, pending.state());
    assertTrue(pending.cancelRequested());
    assertEquals(OwnerState.CANCELLED, as(A, owner.inspect(r)).block(WAIT).state());
    assertEquals("1", usage(A).getReserved());
    assertEquals(0, rows(ownerAdmin, "projects"));
    var error =
        assertThrows(
            StatusRuntimeException.class, () -> as(A, authorityClient.commit(r)).block(WAIT));
    assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    assertEquals("RESERVATION_CANCELLING", error.getStatus().getDescription());
    loseCancelResponse.set(false);
    as(A, root.reconcile()).block(WAIT);
    assertEquals(State.RELEASED, as(A, authorityClient.get(r.operation())).block(WAIT).state());
    assertEquals("0", usage(A).getReserved());
    assertEquals("0", usage(A).getUsed());
  }

  @Test
  void disabledFlagAndMissingAuthorityBillingNeverCreateReservation() {
    enabled.set(false);
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    assertEquals(0, rows(rootAdmin, "quota_reservations"));
    enabled.set(true);
    billingOnline.set(false);
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    assertEquals(0, rows(rootAdmin, "quota_reservations"));
    billingOnline.set(true);
    permitted.set(false);
    assertThrows(Exception.class, () -> reserve(A, UUID.randomUUID(), UUID.randomUUID()));
    assertEquals(0, rows(rootAdmin, "quota_reservations"));
  }
}
