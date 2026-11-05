package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.quota.StorageQuotaProtocol.*;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.issuestorage.StorageQuotaOwner;
import ru.taska.issuestorage.StorageQuotaOwnerGrpcService;
import ru.taska.quota.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;
import ru.taska.storage.versioned.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

/** Production owner + root on separate PostgreSQL app roles, connected through actual mTLS/JWS. */
@Testcontainers
@Timeout(90)
class StorageQuotaIntegrationTest {
  @Container static final PostgreSQLContainer<?> rootPg = new PostgreSQLContainer<>("postgres:16");
  @Container static final PostgreSQLContainer<?> ownerPg = new PostgreSQLContainer<>("postgres:16");

  @Container
  static final GenericContainer<?> s3container =
      new GenericContainer<>("rustfs/rustfs:1.0.0-rc.6")
          .withExposedPorts(9000)
          .withEnv("RUSTFS_ACCESS_KEY", "123")
          .withEnv("RUSTFS_SECRET_KEY", "123");

  static final String BUCKET = "storage-quota-fixture";
  static final UUID PROJECT = UUID.randomUUID(), ISSUE = UUID.randomUUID();
  static S3AsyncClient s3;
  static VersionedObjects objectStore;
  static final AtomicBoolean objectOnline = new AtomicBoolean(true), readOnly = new AtomicBoolean();
  @TempDir static Path certs;
  static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), ACTOR = UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(25);
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static ConnectionPool rootPool, ownerPool;
  static DatabaseClient rootAdmin, ownerAdmin;
  static TenantTransactionExecutor rootTx, ownerTx;
  static Server rootServer, ownerServer;
  static ManagedChannel rootChannel, ownerChannel;
  static GrpcStorageQuotaAuthority authorityClient;
  static StorageQuotaStore root;
  static StorageQuotaOwner owner;
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
    if (service.equals("issue"))
      admin.sql("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public").then().block(WAIT);
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
    bootstrap(ownerPg, "issue");
    rootAdmin = DatabaseClient.create(factory(rootPg, rootPg.getUsername(), rootPg.getPassword()));
    ownerAdmin =
        DatabaseClient.create(factory(ownerPg, ownerPg.getUsername(), ownerPg.getPassword()));
    var rootMigration =
        new TenantConfiguration()
            .registryLiquibase(rootPg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
    rootMigration.afterPropertiesSet();
    rootMigration.afterPropertiesSet();
    var ownerMigration =
        new ru.taska.issuebootstrap.TenantStorageConfiguration()
            .issueTenantLiquibase(ownerPg.getJdbcUrl(), "taska_issue_deployer", DEPLOY);
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
            ConnectionPoolConfiguration.builder(factory(ownerPg, "taska_issue_app", APP))
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
        "/CN=storage-quota-test");
    for (String name : List.of("tenant-service", "issue-service")) {
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
            "issue-service",
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
                    && Set.of("tenant-service", "issue-service")
                        .contains(c.getContext().getIssuerService()));
    var rootSecurity =
        new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, trust);
    var ownerSecurity =
        new RpcContextSecurity("issue-service", Clock.systemUTC(), keys, replay, trust);
    var authorityRef = new AtomicReference<StorageQuotaProtocol.Authority>();
    StorageQuotaProtocol.Authority deferred =
        new StorageQuotaProtocol.Authority() {
          public Mono<Reservation> reserve(Request request) {
            return Mono.defer(() -> authorityRef.get().reserve(request));
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
    s3 =
        S3AsyncClient.builder()
            .endpointOverride(
                java.net.URI.create(
                    "http://" + s3container.getHost() + ":" + s3container.getMappedPort(9000)))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("123", "123")))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build();
    s3.createBucket(b -> b.bucket(BUCKET)).get(15, TimeUnit.SECONDS);
    s3.putBucketVersioning(
            b ->
                b.bucket(BUCKET)
                    .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)))
        .get(15, TimeUnit.SECONDS);
    objectStore = new S3VersionedObjects(s3, BUCKET, Duration.ofSeconds(5));
    VersionedObjects gatedObjects =
        new VersionedObjects() {
          private <T> Mono<T> available(Mono<T> work) {
            return Mono.defer(
                () ->
                    objectOnline.get()
                        ? work
                        : Mono.error(
                            new DomainException(
                                DomainStatus.UNAVAILABLE,
                                "owned fixture object store unavailable")));
          }

          public Mono<Void> requireVersioning() {
            return available(objectStore.requireVersioning());
          }

          public Mono<Measurement> measure(String key, String version, long max) {
            return available(objectStore.measure(key, version, max));
          }

          public Mono<Void> deleteVersion(String key, String version) {
            return available(objectStore.deleteVersion(key, version));
          }

          public Mono<Boolean> purgeKey(String key) {
            return available(objectStore.purgeKey(key));
          }
        };
    owner =
        new StorageQuotaOwner(
            ownerTx,
            deferred,
            gatedObjects,
            (c, use, r, mime) -> {
              if (!ownerOnline.get() || !permitted.get())
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "current policy revoked"));
              if (use == StorageQuotaOwner.Use.DIRECT_DELETE && readOnly.get() && !bypass.get())
                return Mono.error(
                    new DomainException(DomainStatus.FAILED_PRECONDITION, "READ_ONLY"));
              if (mime != null && !"text/plain".equals(mime))
                return Mono.error(
                    new DomainException(DomainStatus.INVALID_ARGUMENT, "MIME_DENIED"));
              return Mono.empty();
            },
            clock);
    var ownerPolicy = new HashMap<String, RpcContextSecurity.Policy>();
    for (String method :
        List.of(
            StorageQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            StorageQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            StorageQuotaOwnerServiceGrpc.getActivateMethod().getFullMethodName(),
            StorageQuotaOwnerServiceGrpc.getManifestsMethod().getFullMethodName()))
      ownerPolicy.put(method, new RpcContextSecurity.Policy(true, false));
    ownerServer =
        server("issue-service")
            .addService(
                ServerInterceptors.intercept(
                    new StorageQuotaOwnerGrpcService(owner),
                    new TenantServerInterceptor(ownerSecurity, ownerPolicy, "test"),
                    new ServerInterceptor() {
                      public <Q, S> ServerCall.Listener<Q> interceptCall(
                          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
                        boolean fault =
                            call.getMethodDescriptor()
                                    .getFullMethodName()
                                    .equals(
                                        StorageQuotaOwnerServiceGrpc.getCancelMethod()
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
        new GrpcStorageQuotaOwner(
            ReactorStorageQuotaOwnerServiceGrpc.newReactorStub(ownerChannel),
            rootSecurity,
            Clock.systemUTC(),
            "tenant-service");
    root =
        new StorageQuotaStore(
            rootTx,
            (c, command, op) -> {
              lastCredential.set(RpcContextBridge.details(c).getContext().getCredentialKind());
              if (!permitted.get())
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "current policy revoked"));
              if (command == StorageQuotaStore.Command.RESERVE && !enabled.get() && !bypass.get())
                return Mono.error(
                    new DomainException(DomainStatus.FAILED_PRECONDITION, "CAPABILITY_DISABLED"));
              return Mono.just(
                  new StorageQuotaStore.Grant(
                      bypass.get(), Duration.ofMinutes(15), 20000000, policyVersion.get()));
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
                    new ProjectQuotaGrpcService(null, root),
                    new TenantServerInterceptor(rootSecurity, rootPolicies, "test")))
            .build()
            .start();
    rootChannel = channel("issue-service", rootServer.getPort());
    authorityClient =
        new GrpcStorageQuotaAuthority(
            ReactorQuotaAuthorityServiceGrpc.newReactorStub(rootChannel),
            ownerSecurity,
            Clock.systemUTC(),
            "issue-service");
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
    if (s3 != null) s3.close();
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
    objectOnline.set(true);
    readOnly.set(false);
    limit.set(100);
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
                + " taska.issues,taska.project_counters,taska.idempotency_keys,taska.issue_history,taska.outbox_events,taska.resource_operation_receipts,taska.attachment_object_manifest,taska.storage_manifest_versions,taska.storage_quota_outbox"
                + " CASCADE")
        .then()
        .block(WAIT);
    for (UUID tenant : List.of(A, B)) {
      ownerAdmin
          .sql("INSERT INTO taska.project_counters(tenant_id,project_id) VALUES(:t,:p)")
          .bind("t", tenant)
          .bind("p", PROJECT)
          .then()
          .block(WAIT);
      ownerAdmin
          .sql(
              "INSERT INTO"
                  + " taska.issues(tenant_id,id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)"
                  + " VALUES(:t,:id,:p,1,'FX-1','TASK','Fixture','OPEN','MEDIUM',:actor)")
          .bind("t", tenant)
          .bind("id", tenant.equals(A) ? ISSUE : UUID.randomUUID())
          .bind("p", PROJECT)
          .bind("actor", ACTOR)
          .then()
          .block(WAIT);
    }
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
                  Long.toString(
                      kind == QuotaLimit.KindValue.KIND_STORAGE_BYTES ? limit.get() : 50)));
    if (readOnly.get()) e.addRestrictions(RestrictionCode.RESTRICTION_CODE_OVER_QUOTA);
    return e.build();
  }

  static <T> Mono<T> as(UUID tenant, Mono<T> work) {
    return RegistryTestContext.withClaims(
        TrustedTenantContext.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setTenantId(tenant.toString())
            .setAction("storageQuota")
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
    return as(
            tenant,
            authorityClient.reserve(
                new Request(operation, resource, PROJECT, ISSUE, 10, time.get().plusSeconds(60))))
        .block(WAIT);
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

  static Reservation reserve(long bytes) {
    return as(
            A,
            authorityClient.reserve(
                new Request(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    PROJECT,
                    ISSUE,
                    bytes,
                    time.get().plusSeconds(60))))
        .block(WAIT);
  }

  static Receipt upload(Reservation r, int bytes) throws Exception {
    as(r.tenant(), owner.prepare(r, "text/plain")).block(WAIT);
    byte[] data = new byte[bytes];
    Arrays.fill(data, (byte) 7);
    var staged =
        s3.putObject(
                b ->
                    b.bucket(BUCKET).key(StorageQuotaOwner.stagingKey(r)).contentType("text/plain"),
                AsyncRequestBody.fromBytes(data))
            .get(15, TimeUnit.SECONDS);
    var copied =
        s3.copyObject(
                b ->
                    b.bucket(BUCKET)
                        .key(StorageQuotaOwner.finalKey(r))
                        .copySource(
                            BUCKET
                                + "/"
                                + StorageQuotaOwner.stagingKey(r)
                                + "?versionId="
                                + staged.versionId()))
            .get(15, TimeUnit.SECONDS);
    return as(r.tenant(), owner.materialize(r, staged.versionId(), copied.versionId())).block(WAIT);
  }

  static Reservation commit(Reservation r) {
    return as(r.tenant(), authorityClient.commit(r)).block(WAIT);
  }

  static String manifestState(Reservation r) {
    return ownerAdmin
        .sql(
            "SELECT state FROM taska.attachment_object_manifest WHERE tenant_id=:t AND"
                + " operation_id=:op")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .map((row, m) -> row.get("state", String.class))
        .one()
        .block(WAIT);
  }

  @Test
  void actualBytesAndTerminalRetriesUsePrimaryObjectProof() throws Exception {
    var r = reserve(10);
    upload(r, 7);
    assertEquals("UPLOADED", manifestState(r));
    assertEquals("0", usage(A).getUsed());
    assertEquals("10", usage(A).getReserved());
    var done = commit(r);
    assertEquals(7, done.actualBytes());
    assertEquals("CONFIRMED", manifestState(r));
    commit(r);
    assertEquals("7", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
    assertEquals(1, rows(rootAdmin, "quota_effects"));
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    as(A, root.releaseUsage(done)).block(WAIT);
    as(A, root.releaseUsage(done)).block(WAIT);
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
    assertEquals("DELETED", manifestState(r));
    assertEquals(2, rows(rootAdmin, "quota_effects"));
  }

  @Test
  void lastBytesSerializeAndConflictDoesNotAlterReservation() {
    limit.set(10);
    var results =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(
                            A,
                            authorityClient.reserve(
                                new Request(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    PROJECT,
                                    ISSUE,
                                    8,
                                    time.get().plusSeconds(60))))
                        .map(r -> true)
                        .onErrorReturn(false),
                2)
            .collectList()
            .block(WAIT);
    assertEquals(1, Collections.frequency(results, true));
    assertEquals("8", usage(A).getReserved());
    var request =
        new Request(
            UUID.randomUUID(), UUID.randomUUID(), PROJECT, ISSUE, 1, time.get().plusSeconds(60));
    var r = as(A, authorityClient.reserve(request)).block(WAIT);
    assertEquals(r, as(A, authorityClient.reserve(request)).block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            as(
                    A,
                    authorityClient.reserve(
                        new Request(
                            request.operation(),
                            request.resource(),
                            PROJECT,
                            ISSUE,
                            2,
                            request.expiresAt())))
                .block(WAIT));
    assertEquals("9", usage(A).getReserved());
  }

  @Test
  void oversizedActualNeverCommitsAndFailedCleanupRetainsReservation() throws Exception {
    var r = reserve(3);
    assertThrows(RuntimeException.class, () -> upload(r, 4));
    assertEquals("RESERVED", manifestState(r));
    assertEquals("0", usage(A).getUsed());
    assertEquals("3", usage(A).getReserved());
    assertThrows(RuntimeException.class, () -> commit(r));
    objectOnline.set(false);
    assertThrows(RuntimeException.class, () -> as(A, authorityClient.release(r)).block(WAIT));
    assertTrue(as(A, authorityClient.get(r.operation())).block(WAIT).cancelRequested());
    assertEquals("3", usage(A).getReserved());
    objectOnline.set(true);
    as(A, authorityClient.release(r)).block(WAIT);
    as(A, authorityClient.release(r)).block(WAIT);
    assertEquals("0", usage(A).getReserved());
    assertThrows(RuntimeException.class, () -> as(A, owner.prepare(r, "text/plain")).block(WAIT));
  }

  @Test
  void deletionFailureNeverReportsFreeBytesAndReconcileUsesPrimaryManifest() throws Exception {
    var r = reserve(10);
    upload(r, 9);
    var done = commit(r);
    objectOnline.set(false);
    assertThrows(
        RuntimeException.class,
        () -> as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT));
    assertEquals("DELETE_PENDING", manifestState(r));
    assertThrows(RuntimeException.class, () -> as(A, root.releaseUsage(done)).block(WAIT));
    assertEquals("9", usage(A).getUsed());
    objectOnline.set(true);
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    as(A, root.reconcileManifests(null, 0)).block(WAIT);
    as(A, root.reconcileManifests(null, 0)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
    assertThrows(RuntimeException.class, () -> as(B, root.releaseUsage(done)).block(WAIT));
    assertEquals("0", usage(B).getUsed());
  }

  @Test
  void signedStorageHopPreservesUserPatAndReadOnlyCannotReserve() {
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setTenantId(A.toString())
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
            .setCredentialId(UUID.randomUUID().toString())
            .setActorGeneration("3")
            .setMembershipGeneration("5")
            .setAction("storageQuota")
            .addScopes("read")
            .build();
    assertThrows(
        RuntimeException.class,
        () ->
            RegistryTestContext.withClaims(
                    claims,
                    authorityClient.reserve(
                        new Request(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            PROJECT,
                            ISSUE,
                            1,
                            time.get().plusSeconds(60))))
                .block(WAIT));
    var done =
        RegistryTestContext.withClaims(
                claims.toBuilder().addScopes("write").build(),
                authorityClient.reserve(
                    new Request(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PROJECT,
                        ISSUE,
                        1,
                        time.get().plusSeconds(60))))
            .block(WAIT);
    assertNotNull(done);
    assertEquals(CredentialKind.CREDENTIAL_KIND_USER_PAT, lastCredential.get());
  }

  @Test
  void putWindowAndLostCancelAcknowledgementCannotCreateFreeLateObject() throws Exception {
    var r = reserve(8);
    as(A, owner.prepare(r, "text/plain")).block(WAIT);
    as(A, owner.recordUploadWindow(r, time.get().plusSeconds(30))).block(WAIT);
    assertThrows(RuntimeException.class, () -> as(A, authorityClient.release(r)).block(WAIT));
    assertEquals("8", usage(A).getReserved());
    assertThrows(RuntimeException.class, () -> upload(r, 7));
    time.set(time.get().plusSeconds(31));
    loseCancelResponse.set(true);
    assertThrows(RuntimeException.class, () -> as(A, authorityClient.release(r)).block(WAIT));
    assertEquals("8", usage(A).getReserved());
    assertThrows(RuntimeException.class, () -> commit(r));
    loseCancelResponse.set(false);
    as(A, root.reconcile()).block(WAIT);
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void boundedManifestEpochAndCounterRepairKeepUsage() throws Exception {
    var r = reserve(10);
    upload(r, 7);
    var done = commit(r);
    var page = as(A, owner.manifests(A, null, 0)).block(WAIT);
    assertTrue(page.complete());
    assertEquals(1, page.items().size());
    rootAdmin
        .sql(
            "UPDATE taska.quota_counters SET used=0,reserved=0 WHERE tenant_id=:t AND"
                + " resource='STORAGE_BYTES'")
        .bind("t", A)
        .then()
        .block(WAIT);
    assertEquals("7", usage(A).getUsed());
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    assertThrows(
        RuntimeException.class, () -> as(A, owner.manifests(A, null, page.version())).block(WAIT));
    as(A, root.reconcileManifests(null, 0)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
  }

  @Test
  void downgradePreservesObjectsAndDoesNotAuthorizeDirectCleanup() throws Exception {
    var r = reserve(10);
    upload(r, 9);
    var done = commit(r);
    limit.set(5);
    entitlement.incrementAndGet();
    readOnly.set(
        as(A, root.restrictions(A))
            .block(WAIT)
            .contains(RestrictionCode.RESTRICTION_CODE_OVER_QUOTA));
    assertTrue(readOnly.get());
    assertEquals("9", usage(A).getUsed());
    assertEquals("5", usage(A).getLimit());
    assertEquals("CONFIRMED", manifestState(r));
    assertThrows(RuntimeException.class, () -> reserve(1));
    assertThrows(
        RuntimeException.class,
        () -> as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT));
    assertEquals("CONFIRMED", manifestState(r));
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.PROJECT_CLEANUP)).block(WAIT);
    as(A, root.releaseUsage(done)).block(WAIT);
    assertTrue(readOnly.get());
    assertEquals("0", usage(A).getUsed());
  }

  @Test
  void realBypassStillCountsInt64ReservationsAndEnforcesFileCap() {
    limit.set(1);
    bypass.set(true);
    Flux.range(0, 110)
        .concatMap(
            i ->
                as(
                    A,
                    authorityClient.reserve(
                        new Request(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            PROJECT,
                            ISSUE,
                            20000000,
                            time.get().plusSeconds(60)))))
        .then()
        .block(Duration.ofSeconds(60));
    assertEquals("2200000000", usage(A).getReserved());
    assertEquals("0", usage(B).getReserved());
    assertThrows(RuntimeException.class, () -> reserve(20000001));
  }

  @Test
  void avatarKeysAndMissingVersioningCannotEnterTenantAccounting() throws Exception {
    assertThrows(
        DomainException.class,
        () -> objectStore.measure("avatar/global-account", "some-version", 2));
    s3.putBucketVersioning(
            b ->
                b.bucket(BUCKET)
                    .versioningConfiguration(c -> c.status(BucketVersioningStatus.SUSPENDED)))
        .get(15, TimeUnit.SECONDS);
    try {
      var r = reserve(2);
      assertThrows(RuntimeException.class, () -> as(A, owner.prepare(r, "text/plain")).block(WAIT));
      assertEquals("2", usage(A).getReserved());
      assertNull(manifestState(r));
    } finally {
      s3.putBucketVersioning(
              b ->
                  b.bucket(BUCKET)
                      .versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)))
          .get(15, TimeUnit.SECONDS);
    }
  }

  @Test
  void fullTwentyMegabyteBoundaryAndPinnedVersionsAreMeasured() throws Exception {
    limit.set(20000000);
    var r = reserve(20000000);
    var receipt = upload(r, 20000000);
    assertEquals(20000000, receipt.actualBytes());
    var done = commit(r);
    assertEquals("20000000", usage(A).getUsed());
    // A later write of mutable latest cannot alter the already pinned resource/measurement.
    s3.putObject(
            b -> b.bucket(BUCKET).key(StorageQuotaOwner.finalKey(r)).contentType("text/plain"),
            AsyncRequestBody.fromBytes(new byte[] {1}))
        .get(15, TimeUnit.SECONDS);
    commit(r);
    assertEquals("20000000", usage(A).getUsed());
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    as(A, root.releaseUsage(done)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
  }

  @Test
  void confirmedResourcesSurviveCounterRestartAndAcceptedFlagOffRetry() throws Exception {
    var r = reserve(10);
    upload(r, 6);
    enabled.set(false);
    var done = commit(r);
    assertEquals("6", usage(A).getUsed());
    var reloaded =
        new StorageQuotaStore(
            new TenantTransactionExecutor(rootPool),
            (c, cmd, target) ->
                Mono.just(new StorageQuotaStore.Grant(false, Duration.ofMinutes(15), 20000000, 1)),
            new Owner() {
              public Mono<Receipt> inspect(Reservation e) {
                return owner.inspect(e);
              }

              public Mono<Receipt> cancel(Reservation e) {
                return owner.cancel(e);
              }

              public Mono<Receipt> activate(Reservation e) {
                return owner.activate(e);
              }

              public Mono<ManifestPage> manifests(UUID t, UUID a, long v) {
                return owner.manifests(t, a, v);
              }
            },
            (t, min) -> Mono.just(terms(t)),
            clock);
    assertEquals(done, as(A, reloaded.get(r.operation())).block(WAIT));
    assertEquals("6", as(A, reloaded.current(A)).block(WAIT).getUsed());
    assertThrows(RuntimeException.class, () -> reserve(1));
    permitted.set(false);
    assertThrows(RuntimeException.class, () -> commit(r));
    permitted.set(true);
    commit(r);
    assertEquals("6", usage(A).getUsed());
  }

  @Test
  void boundedRootRecoveryDoesNotSkipMoreThanHundredExpiredReservations() {
    limit.set(1000);
    for (int i = 0; i < 105; i++) reserve(1);
    time.set(time.get().plusSeconds(61));
    assertEquals(100, as(A, root.reconcile()).block(WAIT).size());
    assertEquals("5", usage(A).getReserved());
    assertEquals(5, as(A, root.reconcile()).block(WAIT).size());
    assertEquals("0", usage(A).getReserved());
    assertEquals(0, as(A, root.reconcile()).block(WAIT).size());
    assertEquals(105, rows(ownerAdmin, "resource_operation_receipts"));
  }

  @Test
  void deletionOutboxRollbackRetainsBytesAndRetriesAlreadyAbsentObjects() throws Exception {
    var r = reserve(10);
    upload(r, 8);
    var done = commit(r);
    ownerAdmin
        .sql(
            "CREATE FUNCTION taska.fail_storage_deleted_outbox() RETURNS trigger LANGUAGE plpgsql"
                + " AS $$BEGIN IF NEW.event_type='AttachmentDeleted' THEN RAISE EXCEPTION 'fixture"
                + " rollback';END IF;RETURN NEW;END$$")
        .then()
        .block(WAIT);
    ownerAdmin
        .sql(
            "CREATE TRIGGER fail_storage_deleted_outbox BEFORE INSERT ON taska.storage_quota_outbox"
                + " FOR EACH ROW EXECUTE FUNCTION taska.fail_storage_deleted_outbox()")
        .then()
        .block(WAIT);
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT));
      assertEquals("DELETE_PENDING", manifestState(r));
      assertEquals("8", usage(A).getUsed());
      assertThrows(RuntimeException.class, () -> as(A, root.releaseUsage(done)).block(WAIT));
    } finally {
      ownerAdmin
          .sql("DROP TRIGGER fail_storage_deleted_outbox ON taska.storage_quota_outbox")
          .then()
          .block(WAIT);
      ownerAdmin.sql("DROP FUNCTION taska.fail_storage_deleted_outbox()").then().block(WAIT);
    }
    as(A, owner.deleteConfirmed(done, StorageQuotaOwner.Use.DIRECT_DELETE)).block(WAIT);
    as(A, root.releaseUsage(done)).block(WAIT);
    assertEquals("0", usage(A).getUsed());
  }
}
