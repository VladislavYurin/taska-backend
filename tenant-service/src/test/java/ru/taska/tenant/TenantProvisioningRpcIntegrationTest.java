package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Message;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.MetadataUtils;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.b2b.v1.CreateTenantRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.GetTenantOperationRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ReactorBillingProvisioningServiceGrpc;
import ru.taska.api.b2b.v1.ReactorBillingServiceGrpc;
import ru.taska.api.b2b.v1.ReactorWorkflowProvisioningServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantServiceGrpc;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.billing.*;
import ru.taska.billing.BillingCatalog;
import ru.taska.billing.BillingCatalogRpc;
import ru.taska.billing.BillingConfiguration;
import ru.taska.billing.BillingProvisioningRpc;
import ru.taska.billing.PeriodCursor;
import ru.taska.billing.SubscriptionDependencies;
import ru.taska.billing.SubscriptionStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.ProvisioningAuthority;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.workflowbootstrap.TenantStorageConfiguration;
import ru.taska.workflowbootstrap.WorkflowProvisioningRpc;
import ru.taska.workflowstorage.TenantWorkflowStore;
import tools.jackson.databind.ObjectMapper;

/**
 * Local service-path E2E: real production handlers, mTLS/JWS, three owner databases and runtime RLS
 * roles.
 */
@Testcontainers
@Timeout(120)
class TenantProvisioningRpcIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> tenantPg = new PostgreSQLContainer<>("postgres:16");

  @Container
  static final PostgreSQLContainer<?> billingPg = new PostgreSQLContainer<>("postgres:16");

  @Container
  static final PostgreSQLContainer<?> workflowPg = new PostgreSQLContainer<>("postgres:16");

  @TempDir static Path certificates;
  static final Duration WAIT = Duration.ofSeconds(20);
  static final Clock CLOCK = Clock.systemUTC();
  static final UUID OWNER = UUID.randomUUID(),
      FREE = UUID.fromString("00000000-0000-8000-8000-000000000001"),
      PAID = UUID.fromString("00000000-0000-8000-8000-000000000002");
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static final List<ConnectionPool> pools = new ArrayList<>();
  static final List<Server> servers = new ArrayList<>();
  static final List<ManagedChannel> channels = new ArrayList<>();
  static DatabaseClient tenantDb, billingDb, workflowDb;
  static TenantRegistryService registry;
  static TenantProvisioning saga;
  static TenantProvisioningWorker worker;
  static TenantServiceGrpc.TenantServiceBlockingStub tenant;
  static RpcContextSecurity gateway, tenantTrust;
  static ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc
          .TenantCreationLifecycleServiceBlockingStub
      closureRpc,
      paymentRpc;
  static RpcContextSecurity billingSigner;
  static TenantCreationLifecycle lifecycle;
  static TenantCreationExpiryWorker expiryWorker;
  static final Map<UUID, TenantCreationDecisions.InitialPayment> decisions =
      new ConcurrentHashMap<>();
  static final java.util.concurrent.atomic.AtomicReference<java.time.Instant> closureNow =
      new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
  static final Clock CLOSURE_CLOCK =
      new Clock() {
        public java.time.Instant instant() {
          return closureNow.get();
        }

        public java.time.ZoneId getZone() {
          return java.time.ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }
      };
  static AtomicBoolean loseWorkflow = new AtomicBoolean(true),
      loseBilling = new AtomicBoolean(true);

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

  static ConnectionPool bootstrap(PostgreSQLContainer<?> pg, String service) throws Exception {
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
    var operator = DatabaseClient.create(factory(pg, pg.getUsername(), pg.getPassword()));
    for (String sql :
        List.of(
            "CREATE SCHEMA " + service + "_liquibase AUTHORIZATION taska_" + service + "_migrator",
            "ALTER ROLE taska_" + service + "_app PASSWORD '" + APP + "'",
            "ALTER ROLE taska_" + service + "_deployer PASSWORD '" + DEPLOY + "'"))
      operator.sql(sql).then().block(WAIT);
    var pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory(pg, "taska_" + service + "_app", APP))
                .initialSize(1)
                .maxSize(2)
                .build());
    pools.add(pool);
    return pool;
  }

  static void migrate(liquibase.integration.spring.SpringLiquibase migration, String module)
      throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("pom.xml"))
        || !Files.isDirectory(root.resolve("tenant-service"))) root = root.getParent();
    Path resources = root.resolve(module + "/src/main/resources");
    migration.setChangeLog(
        resources
            .resolve(
                module.equals("workflow-service")
                    ? "db/workflow-tenant/changelog.json"
                    : "db/changelog/db.changelog-master.yaml")
            .toUri()
            .toString());
    migration.setResourceLoader(
        new DefaultResourceLoader() {
          @Override
          public Resource getResource(String path) {
            String relative = path.replaceFirst("^classpath:/?", "");
            Path own = resources.resolve(relative);
            return Files.exists(own) ? new FileSystemResource(own) : super.getResource(path);
          }
        });
    migration.afterPropertiesSet();
  }

  static ServerInterceptor interceptor(Object config, RpcContextSecurity trust, String method)
      throws Exception {
    var beans = new StaticListableBeanFactory();
    beans.addBean("trust", trust);
    var target =
        config
            .getClass()
            .getDeclaredMethod(
                method, org.springframework.beans.factory.ObjectProvider.class, String.class);
    target.setAccessible(true);
    return (ServerInterceptor)
        target.invoke(config, beans.getBeanProvider(RpcContextSecurity.class), "test");
  }

  static int server(String name, ServerInterceptor interceptor, BindableService... services)
      throws Exception {
    var ssl =
        GrpcSslContexts.forServer(
                certificates.resolve(name + ".crt").toFile(),
                certificates.resolve(name + ".key").toFile())
            .trustManager(certificates.resolve("ca.crt").toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).sslContext(ssl);
    for (var service : services)
      builder.addService(ServerInterceptors.intercept(service, interceptor));
    var server = builder.build().start();
    servers.add(server);
    return server.getPort();
  }

  static ManagedChannel channel(String caller, int port) throws Exception {
    var channel =
        NettyChannelBuilder.forAddress("localhost", port)
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(certificates.resolve("ca.crt").toFile())
                    .keyManager(
                        certificates.resolve(caller + ".crt").toFile(),
                        certificates.resolve(caller + ".key").toFile())
                    .build())
            .build();
    channels.add(channel);
    return channel;
  }

  @BeforeAll
  static void setup() throws Exception {
    openssl(
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
        "/CN=fixture-ca");
    for (String name :
        List.of("gateway", "tenant-service", "billing-service", "workflow-service")) {
      openssl(
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=" + name);
      Files.writeString(
          certificates.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      openssl(
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
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("fixture", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String key) {
            return List.of("gateway", "tenant-service", "billing-service").contains(issuer)
                    && key.equals("fixture")
                ? pair.getPublic()
                : null;
          }
        };
    var used = ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.ReplayGuard replay =
        (issuer, id, until) -> Mono.just(used.add(issuer + ":" + id));
    RpcContextSecurity.Authority authority =
        (context, rpc, policy) ->
            Mono.just(
                List.of("tenant-service", "billing-service")
                            .contains(context.getContext().getIssuerService())
                        && context.getContext().getCredentialKind()
                            == CredentialKind.CREDENTIAL_KIND_SERVICE
                    || context.getContext().getIssuerService().equals("gateway")
                        && context.getContext().getCredentialKind()
                            == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                        && context.getContext().hasSessionId()
                        && context.getContext().getActorUserId().equals(OWNER.toString()));
    gateway = new RpcContextSecurity("gateway", CLOCK, keys, replay, authority);
    tenantTrust = new RpcContextSecurity("tenant-service", CLOCK, keys, replay, authority);
    var billingTrust = new RpcContextSecurity("billing-service", CLOCK, keys, replay, authority);
    billingSigner = billingTrust;
    var workflowTrust = new RpcContextSecurity("workflow-service", CLOCK, keys, replay, authority);
    var tenantPool = bootstrap(tenantPg, "tenant");
    var billingPool = bootstrap(billingPg, "billing");
    var workflowPool = bootstrap(workflowPg, "workflow");
    migrate(
        new TenantConfiguration()
            .registryLiquibase(tenantPg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY),
        "tenant-service");
    var bc = new BillingConfiguration();
    var bm =
        bc.getClass()
            .getDeclaredMethod("registryLiquibase", String.class, String.class, String.class);
    bm.setAccessible(true);
    migrate(
        (liquibase.integration.spring.SpringLiquibase)
            bm.invoke(bc, billingPg.getJdbcUrl(), "taska_billing_deployer", DEPLOY),
        "billing-service");
    migrate(
        new TenantStorageConfiguration()
            .workflowTenantLiquibase(workflowPg.getJdbcUrl(), "taska_workflow_deployer", DEPLOY),
        "workflow-service");
    tenantDb =
        DatabaseClient.create(factory(tenantPg, tenantPg.getUsername(), tenantPg.getPassword()));
    billingDb =
        DatabaseClient.create(factory(billingPg, billingPg.getUsername(), billingPg.getPassword()));
    workflowDb =
        DatabaseClient.create(
            factory(workflowPg, workflowPg.getUsername(), workflowPg.getPassword()));
    var btx = TransactionalOperator.create(new R2dbcTransactionManager(billingPool));
    var catalog = new BillingCatalog(DatabaseClient.create(billingPool), btx, null);
    var subscriptions =
        new SubscriptionStore(
            new TenantTransactionExecutor(billingPool),
            v -> Mono.just(false),
            new SubscriptionDependencies.SettlementEvidenceAuthority() {
              public Mono<SubscriptionDependencies.CoverageEvidence> confirmed(
                  DatabaseClient db, UUID t, UUID e) {
                return Mono.empty();
              }

              public Mono<SubscriptionDependencies.RevocationEvidence> reversed(
                  DatabaseClient db, UUID t, UUID e) {
                return Mono.empty();
              }
            },
            CLOCK,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), CLOCK));
    int billingPort =
        server(
            "billing-service",
            interceptor(bc, billingTrust, "registrySecurity"),
            new BillingCatalogRpc(catalog, subscriptions, null),
            new BillingProvisioningRpc(subscriptions));
    var workflows =
        new TenantWorkflowStore(new TenantTransactionExecutor(workflowPool), new ObjectMapper());
    int workflowPort =
        server(
            "workflow-service",
            interceptor(
                new TenantStorageConfiguration(), workflowTrust, "workflowTenantTransportClosed"),
            new WorkflowProvisioningRpc(workflows));
    var billingChannel = channel("tenant-service", billingPort);
    var workflowChannel = channel("tenant-service", workflowPort);
    var dependencies =
        new GrpcProvisioningDependencies(
            ReactorBillingProvisioningServiceGrpc.newReactorStub(billingChannel),
            ReactorWorkflowProvisioningServiceGrpc.newReactorStub(workflowChannel),
            tenantTrust,
            CLOCK);
    var tx = new TenantTransactionExecutor(tenantPool);
    registry =
        new TenantRegistryService(
            tx,
            new GrpcPublishedPlans(
                ReactorBillingServiceGrpc.newReactorStub(billingChannel), tenantTrust, CLOCK),
            (db, actor, t) -> Mono.empty(),
            CLOCK,
            Duration.ofDays(30));
    saga =
        new TenantProvisioning(
            tx,
            new TenantProvisioning.Dependencies() {
              public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                return dependencies
                    .workflow(t, o, template)
                    .then(
                        Mono.defer(
                            () ->
                                loseWorkflow.getAndSet(false)
                                    ? Mono.error(Status.UNAVAILABLE.asRuntimeException())
                                    : Mono.empty()));
              }

              public Mono<Void> billing(UUID t, UUID o, UUID plan, boolean free) {
                return dependencies
                    .billing(t, o, plan, free)
                    .then(
                        Mono.defer(
                            () ->
                                loseBilling.getAndSet(false)
                                    ? Mono.error(Status.UNAVAILABLE.asRuntimeException())
                                    : Mono.empty()));
              }
            },
            CLOCK);
    worker =
        new TenantProvisioningWorker(
            DatabaseClient.create(tenantPool),
            () -> new ProvisioningAuthority(tenantTrust, CLOCK),
            saga);
    lifecycle =
        new TenantCreationLifecycle(
            tx, (t, id) -> Mono.justOrEmpty(decisions.get(id)), CLOSURE_CLOCK);
    expiryWorker =
        new TenantCreationExpiryWorker(
            DatabaseClient.create(tenantPool),
            () -> new ru.taska.security.tenant.TenantLifecycleAuthority(tenantTrust, CLOCK),
            lifecycle,
            CLOSURE_CLOCK);
    int tenantPort =
        server(
            "tenant-service",
            interceptor(new TenantConfiguration(), tenantTrust, "registrySecurity"),
            new TenantGrpcService(
                registry,
                org.mockito.Mockito.mock(TenantMembershipService.class),
                org.mockito.Mockito.mock(SeatQuotaStore.class)),
            new TenantProvisioningRpc(saga),
            new TenantCreationLifecycleGrpc(lifecycle));
    closureRpc =
        ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc.newBlockingStub(
                channel("tenant-service", tenantPort))
            .withDeadlineAfter(15, TimeUnit.SECONDS);
    paymentRpc =
        ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc.newBlockingStub(
                channel("billing-service", tenantPort))
            .withDeadlineAfter(15, TimeUnit.SECONDS);
    tenant =
        TenantServiceGrpc.newBlockingStub(channel("gateway", tenantPort))
            .withDeadlineAfter(15, TimeUnit.SECONDS);
  }

  static void openssl(String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add("openssl");
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command)
            .directory(certificates.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertEquals(0, process.waitFor());
  }

  static RequestContext context(String action) {
    var now = CLOCK.instant();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setContextVersion(1)
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                .setActorUserId(OWNER.toString())
                .setActorGeneration("1")
                .setSessionId(UUID.randomUUID().toString())
                .setIssuerService("gateway")
                .setAudienceService("tenant-service")
                .setAction(action)
                .addScopes("read")
                .addScopes("write")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }

  static Metadata signature(Message request, String rpc, boolean write) {
    var policy = new RpcContextSecurity.Policy(false, write);
    var verified = gateway.authorize(RpcContextSecurity.context(request), rpc, policy).block(WAIT);
    var result = new Metadata();
    result.put(
        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
        gateway.sign(verified, request, rpc, policy).block(WAIT));
    return result;
  }

  static long count(DatabaseClient db, String table) {
    return db.sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void signedCreationResumesAcrossLostRemoteAcksAndReadsFinalOperation() throws Exception {
    try (var scheduler =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      scheduler
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "fixture", Map.of("taska.tenant.provisioning-poll-ms", "20")));
      scheduler.register(SchedulingFixture.class);
      scheduler.registerBean(TenantProvisioningWorker.class, () -> worker);
      scheduler.refresh();
      for (UUID plan : List.of(FREE, PAID)) {
        String key = UUID.randomUUID().toString();
        var base =
            CreateTenantRpcRequest.newBuilder()
                .setPayload(
                    ru.taska.api.b2b.v1.CreateTenantRequest.newBuilder()
                        .setName("RPC fixture")
                        .setPlanVersionId(plan.toString()))
                .setMutation(
                    ru.taska.api.b2b.v1.MutationOptions.newBuilder().setIdempotencyKey(key));
        var request = base.setContext(context("createTenant")).build();
        assertEquals(
            Status.Code.UNAUTHENTICATED,
            assertThrows(StatusRuntimeException.class, () -> tenant.createTenant(request))
                .getStatus()
                .getCode());
        var signed =
            tenant.withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    signature(
                        request,
                        TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                        true)));
        assertEquals(
            Status.Code.UNAUTHENTICATED,
            assertThrows(
                    StatusRuntimeException.class,
                    () ->
                        signed.createTenant(
                            request.toBuilder()
                                .setPayload(request.getPayload().toBuilder().setName("tampered"))
                                .build()))
                .getStatus()
                .getCode());
        var created = signed.createTenant(request);
        assertEquals(Operation.StateValue.STATE_PENDING, created.getOperation().getState());
        org.awaitility.Awaitility.await()
            .atMost(Duration.ofSeconds(25))
            .pollInterval(Duration.ofMillis(100))
            .until(
                () ->
                    "SUCCEEDED"
                        .equals(
                            tenantDb
                                .sql("SELECT state FROM taska.tenant_operations WHERE id=:id")
                                .bind("id", UUID.fromString(created.getOperation().getId()))
                                .map((r, m) -> r.get("state", String.class))
                                .one()
                                .block(WAIT)));
        var read =
            GetTenantOperationRpcRequest.newBuilder()
                .setContext(context("getTenantOperation"))
                .setTenantId(created.getTenant().getId())
                .setOperationId(created.getOperation().getId())
                .build();
        var operation =
            tenant
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(
                        signature(
                            read,
                            TenantServiceGrpc.getGetTenantOperationMethod().getFullMethodName(),
                            false)))
                .getTenantOperation(read);
        assertEquals(Operation.StateValue.STATE_SUCCEEDED, operation.getState());
        var repeat = base.setContext(context("createTenant")).build();
        var replay =
            tenant
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(
                        signature(
                            repeat,
                            TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                            true)))
                .createTenant(repeat);
        assertEquals(operation, replay.getOperation());
        assertEquals(created.getTenant().getId(), replay.getTenant().getId());
        assertEquals(
            plan.equals(FREE)
                ? Tenant.LifecycleValue.LIFECYCLE_ACTIVE
                : Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT,
            replay.getTenant().getLifecycle());
        if (plan.equals(PAID)) {
          assertEquals(
              created.getTenant().getCreationDeadline(), replay.getTenant().getCreationDeadline());
          assertEquals(1, replay.getTenant().getRestrictionsCount());
        }
      }
      assertEquals(2, count(tenantDb, "tenants"));
      assertEquals(2, count(tenantDb, "tenant_memberships"));
      assertEquals(2, count(billingDb, "subscriptions"));
      assertEquals(0, count(billingDb, "subscription_periods"));
      assertEquals(2, count(workflowDb, "tenant_workflow_templates"));
      assertEquals(2, count(workflowDb, "workflow_provisioning_receipts"));
      assertEquals(0, count(workflowDb, "workflows"));
      assertEquals(4, count(tenantDb, "outbox_events"));
      assertFalse(loseWorkflow.get());
      assertFalse(loseBilling.get());
      closureAndDecisionRpcFlow();
      planDowngradePreservesRootStorage();
      verifiedSettlementAndRootActivationRpcFlow();
    }
  }

  static Metadata serviceSignature(Message request, String rpc, RpcContextSecurity signer) {
    var policy = new RpcContextSecurity.Policy(true, true);
    var verified = signer.authorize(RpcContextSecurity.context(request), rpc, policy).block(WAIT);
    var result = new Metadata();
    result.put(
        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
        signer.sign(verified, request, rpc, policy).block(WAIT));
    return result;
  }

  void closureAndDecisionRpcFlow() {
    var request =
        CreateTenantRpcRequest.newBuilder()
            .setContext(context("createTenant"))
            .setPayload(
                ru.taska.api.b2b.v1.CreateTenantRequest.newBuilder()
                    .setName("Closure RPC")
                    .setPlanVersionId(PAID.toString()))
            .setMutation(
                ru.taska.api.b2b.v1.MutationOptions.newBuilder()
                    .setIdempotencyKey(UUID.randomUUID().toString()))
            .build();
    var created =
        tenant
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    signature(
                        request,
                        TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                        true)))
            .createTenant(request);
    UUID id = UUID.fromString(created.getTenant().getId());
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                "SUCCEEDED"
                    .equals(
                        tenantDb
                            .sql("SELECT state FROM taska.tenant_operations WHERE tenant_id=:id")
                            .bind("id", id)
                            .map((r, m) -> r.get("state", String.class))
                            .one()
                            .block(WAIT)));
    closureNow.set(java.time.Instant.parse(created.getTenant().getCreationDeadline()));
    try (var scheduler =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      scheduler
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "expiry", Map.of("taska.tenant.creation-expiry-poll-ms", "20")));
      scheduler.register(SchedulingFixture.class);
      scheduler.registerBean(TenantCreationExpiryWorker.class, () -> expiryWorker);
      scheduler.refresh();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  "AUTO_CLOSED"
                      .equals(
                          tenantDb
                              .sql("SELECT lifecycle FROM taska.tenants WHERE id=:id")
                              .bind("id", id)
                              .map((r, m) -> r.get("lifecycle", String.class))
                              .one()
                              .block(WAIT)));
    }
    var expire =
        ru.taska.api.b2b.v1.ExpireTenantCreationRequest.newBuilder()
            .setTenantId(id.toString())
            .setContext(
                ProvisioningAuthority.context(id, "tenant-service", "expireTenantCreation", CLOCK))
            .build();
    assertEquals(
        Status.Code.UNAUTHENTICATED,
        assertThrows(StatusRuntimeException.class, () -> closureRpc.expireTenantCreation(expire))
            .getStatus()
            .getCode());
    var closed =
        closureRpc
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    serviceSignature(
                        expire,
                        ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc
                            .getExpireTenantCreationMethod()
                            .getFullMethodName(),
                        tenantTrust)))
            .expireTenantCreation(expire);
    assertEquals(
        Tenant.CreationClosureReason.CREATION_CLOSURE_REASON_AUTO_EXPIRED,
        closed.getCreationClosureReason());
    var e =
        new TenantCreationDecisions.InitialPayment(
            UUID.randomUUID(),
            id,
            UUID.fromString(created.getOperation().getId()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            PAID,
            true,
            UUID.randomUUID(),
            TenantCreationDecisions.Precision.EXACT,
            closureNow.get(),
            null,
            null,
            closureNow.get());
    decisions.put(e.decisionId(), e);
    var serviceContext =
        ProvisioningAuthority.context(
            id, "tenant-service", "applyTenantInitialPaymentDecision", CLOCK);
    var payment =
        ru.taska.api.b2b.v1.ApplyTenantInitialPaymentDecisionRequest.newBuilder()
            .setTenantId(id.toString())
            .setDecisionId(e.decisionId().toString())
            .setContext(
                serviceContext.toBuilder()
                    .setContext(
                        serviceContext.getContext().toBuilder()
                            .setIssuerService("billing-service")))
            .build();
    var result =
        paymentRpc
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    serviceSignature(
                        payment,
                        ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc
                            .getApplyTenantInitialPaymentDecisionMethod()
                            .getFullMethodName(),
                        billingSigner)))
            .applyTenantInitialPaymentDecision(payment);
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, result.getTenant().getLifecycle());
    var c =
        ru.taska.api.b2b.v1.CancelTenantCreationRpcRequest.newBuilder()
            .setTenantId(created.getTenant().getId())
            .setContext(context("cancelTenantCreation"))
            .setPayload(ru.taska.api.b2b.v1.ConfirmedAction.newBuilder().setConfirmed(true))
            .setMutation(
                ru.taska.api.b2b.v1.MutationOptions.newBuilder()
                    .setIdempotencyKey(UUID.randomUUID().toString())
                    .setExpectedVersion(result.getTenant().getVersion()))
            .build();
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        assertThrows(
                StatusRuntimeException.class,
                () ->
                    tenant
                        .withInterceptors(
                            MetadataUtils.newAttachHeadersInterceptor(
                                signature(
                                    c,
                                    TenantServiceGrpc.getCancelTenantCreationMethod()
                                        .getFullMethodName(),
                                    true)))
                        .cancelTenantCreation(c))
            .getStatus()
            .getCode());
    var another =
        request.toBuilder()
            .setContext(context("createTenant"))
            .setMutation(
                request.getMutation().toBuilder().setIdempotencyKey(UUID.randomUUID().toString()))
            .build();
    var unpaidCreated =
        tenant
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    signature(
                        another,
                        TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                        true)))
            .createTenant(another);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                "SUCCEEDED"
                    .equals(
                        tenantDb
                            .sql("SELECT state FROM taska.tenant_operations WHERE tenant_id=:id")
                            .bind("id", UUID.fromString(unpaidCreated.getTenant().getId()))
                            .map((r, m) -> r.get("state", String.class))
                            .one()
                            .block(WAIT)));
    // Read the current optimistic version: the independent provisioning scheduler may have advanced
    // it.
    String version =
        tenantDb
            .sql("SELECT version::text v FROM taska.tenants WHERE id=:id")
            .bind("id", UUID.fromString(unpaidCreated.getTenant().getId()))
            .map((r, m) -> r.get("v", String.class))
            .one()
            .block(WAIT);
    var cancel =
        c.toBuilder()
            .setTenantId(unpaidCreated.getTenant().getId())
            .setContext(context("cancelTenantCreation"))
            .setMutation(c.getMutation().toBuilder().setExpectedVersion(version))
            .build();
    var cancelled =
        tenant
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    signature(
                        cancel,
                        TenantServiceGrpc.getCancelTenantCreationMethod().getFullMethodName(),
                        true)))
            .cancelTenantCreation(cancel);
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, cancelled.getLifecycle());
    assertEquals(
        0,
        count(billingDb, "subscription_periods")); // fake decision did not settle or mint a period.
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.scheduling.annotation.EnableScheduling
  static class SchedulingFixture {}

  @AfterAll
  static void cleanup() throws Exception {
    for (var channel : channels) channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    for (var server : servers) server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    for (var pool : pools) pool.disposeLater().block(WAIT);
  }

  // Actual billing and Root PostgreSQL owners; settlement/primary policy are explicit fixture
  // ports.
  void planDowngradePreservesRootStorage() {
    UUID t = UUID.randomUUID(), invoice = UUID.randomUUID(), fact = UUID.randomUUID();
    tenantDb
        .sql(
            """
            DO $$ BEGIN
              INSERT INTO taska.tenants SELECT (jsonb_populate_record(NULL::taska.tenants,to_jsonb(p)||jsonb_build_object('id','%s','creation_operation_id','%s','owner_id','%s'))).* FROM taska.tenants p WHERE lifecycle='ACTIVE' LIMIT 1;
              INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');
            END $$
            """
                .formatted(t, UUID.randomUUID(), OWNER, t, OWNER))
        .then()
        .block(WAIT);
    var btx = new TenantTransactionExecutor(pools.get(1));
    var now = CLOCK.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var period = SubscriptionCalendar.initial(now);
    var evidence =
        new SubscriptionDependencies.CoverageEvidence(
            t,
            invoice,
            fact,
            PAID,
            UUID.randomUUID(),
            period.startsAt(),
            period.endsAt(),
            period.anchor().day(),
            period.anchor().timeUtc(),
            now,
            SubscriptionDependencies.PaymentSource.PSP,
            List.of());
    var subscription =
        new SubscriptionStore(
            btx,
            v -> Mono.just(OWNER.toString().equals(v.actorId())),
            new SubscriptionDependencies.SettlementEvidenceAuthority() {
              public Mono<SubscriptionDependencies.CoverageEvidence> confirmed(
                  DatabaseClient db, UUID tenant, UUID id) {
                return Mono.just(evidence);
              }

              public Mono<SubscriptionDependencies.RevocationEvidence> reversed(
                  DatabaseClient db, UUID tenant, UUID id) {
                return Mono.empty();
              }
            },
            CLOCK,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), CLOCK));
    var service =
        TrustedTenantContext.newBuilder()
            .setTenantId(t.toString())
            .setActorGeneration("1")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setIssuerService("tenant-service")
            .setAudienceService("billing-service")
            .setAction("initializeSubscription")
            .addScopes("write")
            .build();
    ru.taska.security.tenant.RegistryTestContext.withClaims(
            service, subscription.initialize(t, PAID, "initialize"))
        .block(WAIT);
    billingDb
        .sql(
            """
            DO $$ BEGIN
            INSERT INTO taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,currency,currency_exponent,subtotal_minor,tax_minor,total_minor,issued_at,payable_until,terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot)
              VALUES('%s','%s','FIXTURE-%s','%s','%s','INITIAL','%s','PSP','EUR',2,2000,0,2000,now(),now()+interval '7 days','{}','{}','{}','{}','');
            INSERT INTO taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot) VALUES('%s','%s',1,'Fixture',1,2000,0,2000,'[]','');
            UPDATE taska.invoices SET state='OPEN' WHERE tenant_id='%s';
            END $$
            """
                .formatted(invoice, t, invoice, UUID.randomUUID(), invoice, PAID, t, invoice, t))
        .then()
        .block(WAIT);
    var paid =
        ru.taska.security.tenant.RegistryTestContext.withClaims(
                service.toBuilder()
                    .setIssuerService("billing-service")
                    .setAction("applySubscriptionCoverage")
                    .build(),
                subscription.grant(t, fact, "confirmed-fixture"))
            .block(WAIT);
    var entitlement = new EntitlementStore(btx, v -> Mono.just(true), CLOCK);
    var owner =
        TrustedTenantContext.newBuilder()
            .setActorUserId(OWNER.toString())
            .setTenantId(t.toString())
            .setActorGeneration("1")
            .setMembershipGeneration("1")
            .setSessionId(UUID.randomUUID().toString())
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .setIssuerService("api-gateway")
            .setAudienceService("billing-service")
            .setAction("changeSubscriptionPlan")
            .addScopes("write")
            .addScopes("read")
            .build();
    ru.taska.entitlement.EntitlementProjection.Source terms =
        (tenant, min) ->
            ru.taska.security.tenant.RegistryTestContext.withClaims(
                owner.toBuilder().setAction("getEntitlement").build(),
                entitlement.get(
                    GetEntitlementRpcRequest.newBuilder().setTenantId(t.toString()).build()));
    var root =
        new StorageQuotaStore(
            new TenantTransactionExecutor(pools.get(0)),
            (c, command, target) ->
                Mono.just(new StorageQuotaStore.Grant(false, Duration.ofMinutes(15), 20000000, 1)),
            org.mockito.Mockito.mock(ru.taska.quota.StorageQuotaProtocol.Owner.class),
            terms,
            CLOCK);
    long freeLimit =
        billingDb
            .sql("SELECT max_storage_bytes FROM taska.catalog_plan_versions WHERE id=:id")
            .bind("id", FREE)
            .map((r, m) -> r.get("max_storage_bytes", Long.class))
            .one()
            .block(WAIT);
    tenantDb
        .sql(
            "INSERT INTO taska.quota_counters(tenant_id,resource,scope_id)"
                + " VALUES(:t,'STORAGE_BYTES',:t)")
        .bind("t", t)
        .then()
        .block(WAIT);
    tenantDb
        .sql(
            "INSERT INTO"
                + " taska.quota_effects(tenant_id,operation_id,resource,scope_id,effect_kind,amount,resource_id,owner_version,applied_at)"
                + " VALUES(:t,:op,'STORAGE_BYTES',:t,'COMMIT',:bytes,:resource,1,now())")
        .bind("t", t)
        .bind("op", UUID.randomUUID())
        .bind("bytes", freeLimit + 1)
        .bind("resource", UUID.randomUUID())
        .then()
        .block(WAIT);
    var rootClaims =
        owner.toBuilder().setAudienceService("tenant-service").setAction("storageQuota").build();
    var before =
        ru.taska.security.tenant.RegistryTestContext.withClaims(rootClaims, root.current(t))
            .block(WAIT);
    assertEquals(Long.toString(freeLimit + 1), before.getUsed());
    var commands =
        new SubscriptionPlanChanges(
            subscription,
            (c, command) -> Mono.just(new PlanChangeDependencies.Grant(OWNER, 1)),
            new PlanChangeDependencies.FreeAuthority() {
              public Mono<PlanChangeDependencies.FreeProof> admit(
                  UUID tenant, UUID op, UUID actor, long version) {
                return Mono.just(new PlanChangeDependencies.FreeProof(tenant, op, actor, version));
              }

              public Mono<PlanChangeDependencies.FreeProof> accepted(
                  UUID tenant, UUID op, UUID actor, long version) {
                return admit(tenant, op, actor, version);
              }

              public Mono<PlanChangeDependencies.FreeProof> cancel(
                  UUID tenant, UUID op, UUID actor, long version) {
                return Mono.just(
                    new PlanChangeDependencies.FreeProof(tenant, op, actor, version, false));
              }
            },
            CLOCK);
    ru.taska.security.tenant.RegistryTestContext.withClaims(
            owner,
            commands.change(
                ChangeSubscriptionPlanRpcRequest.newBuilder()
                    .setTenantId(t.toString())
                    .setPayload(
                        ChangePlanRequest.newBuilder()
                            .setPlanVersionId(FREE.toString())
                            .setConfirmed(true))
                    .setMutation(
                        MutationOptions.newBuilder()
                            .setExpectedVersion(paid.getVersion())
                            .setIdempotencyKey("downgrade"))
                    .build()))
        .block(WAIT);
    var after =
        ru.taska.security.tenant.RegistryTestContext.withClaims(rootClaims, root.current(t))
            .block(WAIT);
    assertEquals(before.getUsed(), after.getUsed());
    assertEquals(before.getReserved(), after.getReserved());
    assertEquals(Long.toString(freeLimit), after.getLimit());
    org.junit.jupiter.api.Assertions.assertTrue(
        ru.taska.security.tenant.RegistryTestContext.withClaims(rootClaims, root.restrictions(t))
            .block(WAIT)
            .contains(RestrictionCode.RESTRICTION_CODE_OVER_QUOTA));

    // The real common evaluator consumes actual billing terms and actual Root quota causes.
    var target =
        new ru.taska.capability.CapabilityFacts.Target(
            t, UUID.randomUUID(), UUID.randomUUID(), null);
    var flags =
        new java.util.EnumMap<ru.taska.capability.CapabilityFacts.Flag, Boolean>(
            ru.taska.capability.CapabilityFacts.Flag.class);
    for (var flag : ru.taska.capability.CapabilityFacts.Flag.values()) flags.put(flag, true);
    var evaluator =
        new ru.taska.capability.CapabilityEvaluator(
            (context, scope, actions) ->
                terms
                    .current(t, 0)
                    .zipWith(root.restrictions(t))
                    .map(
                        current -> {
                          var claims =
                              ru.taska.security.tenant.RpcContextBridge.details(context)
                                  .getContext();
                          var invariants =
                              new java.util.EnumMap<
                                  ru.taska.capability.Action, java.util.Set<ErrorCode>>(
                                  ru.taska.capability.Action.class);
                          actions.forEach(action -> invariants.put(action, java.util.Set.of()));
                          return new ru.taska.capability.CapabilityFacts(
                              OWNER,
                              scope,
                              context.requestId(),
                              CLOCK.instant().plusSeconds(10),
                              new ru.taska.capability.CapabilityFacts.Identity(
                                  true,
                                  false,
                                  "1",
                                  true,
                                  "",
                                  claims.getSessionId(),
                                  false,
                                  java.util.Set.of("read", "write")),
                              MembershipSnapshot.newBuilder()
                                  .setTenantId(t.toString())
                                  .setUserId(OWNER.toString())
                                  .setRole(Membership.RoleValue.ROLE_TENANT_ADMIN)
                                  .setState(Membership.StateValue.STATE_ACTIVE)
                                  .setVersion("1")
                                  .setTenantMembershipVersion("1")
                                  .setIsOwner(true)
                                  .build(),
                              ru.taska.capability.CapabilityFacts.ProjectRole.ADMIN,
                              Tenant.LifecycleValue.LIFECYCLE_ACTIVE,
                              true,
                              true,
                              null,
                              current.getT1(),
                              ru.taska.capability.CapabilityFacts.PlanKind.FREE,
                              current.getT2(),
                              flags,
                              "1",
                              ru.taska.capability.CapabilityFacts.Channel.DIRECT,
                              ru.taska.capability.CapabilityFacts.PaymentChannel.NONE,
                              false,
                              invariants,
                              invariants,
                              null,
                              null);
                        }),
            CLOCK);
    var policyClaims =
        rootClaims.toBuilder().setExpiresAt(CLOCK.instant().plusSeconds(60).toString()).build();
    var effective =
        ru.taska.security.tenant.RegistryTestContext.withClaims(
                policyClaims, evaluator.evaluate(ru.taska.capability.Action.ISSUE_UPDATE, target))
            .block(WAIT);
    org.junit.jupiter.api.Assertions.assertFalse(effective.capability().getAllowed());
    org.junit.jupiter.api.Assertions.assertTrue(
        effective.capability().getDenialCodesList().contains(ErrorCode.ERROR_CODE_READ_ONLY));

    assertEquals(
        1L,
        billingDb
            .sql(
                "SELECT count(*) n FROM taska.subscription_periods WHERE tenant_id=:t AND"
                    + " status='SUPERSEDED'")
            .bind("t", t)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        1L,
        billingDb
            .sql("SELECT count(*) n FROM taska.invoices WHERE tenant_id=:t")
            .bind("t", t)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  /**
   * Actual billing settlement -> primary Root -> billing decision -> Root activation, over mTLS.
   */
  void verifiedSettlementAndRootActivationRpcFlow() throws Exception {
    closureNow.set(CLOCK.instant());
    var create =
        CreateTenantRpcRequest.newBuilder()
            .setContext(context("createTenant"))
            .setPayload(
                ru.taska.api.b2b.v1.CreateTenantRequest.newBuilder()
                    .setName("Verified settlement RPC")
                    .setPlanVersionId(PAID.toString()))
            .setMutation(
                MutationOptions.newBuilder().setIdempotencyKey(UUID.randomUUID().toString()))
            .build();
    var created =
        tenant
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    signature(
                        create,
                        TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                        true)))
            .createTenant(create);
    UUID t = UUID.fromString(created.getTenant().getId()),
        op = UUID.fromString(created.getOperation().getId()),
        invoice = UUID.randomUUID(),
        evidenceId = UUID.randomUUID();
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                "SUCCEEDED"
                    .equals(
                        tenantDb
                            .sql("SELECT state FROM taska.tenant_operations WHERE tenant_id=:t")
                            .bind("t", t)
                            .map((r, m) -> r.get("state", String.class))
                            .one()
                            .block(WAIT)));
    var occurred = CLOCK.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    billingDb
        .sql(
            """
            DO $$ BEGIN
              INSERT INTO taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,currency,currency_exponent,
                subtotal_minor,tax_minor,total_minor,issued_at,payable_until,creation_deadline_snapshot,terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot)
              VALUES('%s','%s','RPC-%s','%s','first','INITIAL','%s','PSP','EUR',2,2000,0,2000,'%s','%s','%s','{}','{"profileVersion":"fixture-v1"}','{}','{}','');
              INSERT INTO taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot)
              VALUES('%s','%s',1,'Monthly subscription',1,2000,0,2000,'[]','');
              UPDATE taska.invoices SET state='OPEN' WHERE tenant_id='%s' AND id='%s';
            END $$
            """
                .formatted(
                    invoice,
                    t,
                    invoice,
                    op,
                    PAID,
                    occurred.minusSeconds(1),
                    occurred.plusSeconds(7 * 86400),
                    created.getTenant().getCreationDeadline(),
                    t,
                    invoice,
                    t,
                    invoice))
        .then()
        .block(WAIT);
    var decisionStub =
        new java.util.concurrent.atomic.AtomicReference<
            ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub>();
    var realLifecycle =
        new TenantCreationLifecycle(
            new TenantTransactionExecutor(pools.get(0)),
            (tenantId, decision) ->
                new GrpcInitialPaymentDecisions(decisionStub.get(), tenantTrust, CLOCK)
                    .resolve(tenantId, decision),
            CLOSURE_CLOCK);
    int rootPort =
        server(
            "tenant-service",
            interceptor(new TenantConfiguration(), tenantTrust, "registrySecurity"),
            new TenantPaymentStateGrpc(realLifecycle),
            new TenantCreationLifecycleGrpc(realLifecycle));
    var rootChannel = channel("billing-service", rootPort);
    var btx = new TenantTransactionExecutor(pools.get(1));
    var emptyEvidence =
        new SubscriptionDependencies.SettlementEvidenceAuthority() {
          public Mono<SubscriptionDependencies.CoverageEvidence> confirmed(
              DatabaseClient db, UUID tenantId, UUID proof) {
            return Mono.empty();
          }

          public Mono<SubscriptionDependencies.RevocationEvidence> reversed(
              DatabaseClient db, UUID tenantId, UUID proof) {
            return Mono.empty();
          }
        };
    var subscriptions =
        new SubscriptionStore(
            btx,
            c -> Mono.just(false),
            emptyEvidence,
            CLOSURE_CLOCK,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), CLOSURE_CLOCK));
    var ledger =
        new FinancialLedger(
            btx,
            c -> Mono.just(false),
            (db, tenantId, proof) -> Mono.empty(),
            (db, tenantId, i, ids) -> Mono.empty(),
            CLOSURE_CLOCK);
    var normalized =
        new FinancialLedgerDependencies.Movement(
            t,
            new FinancialLedgerDependencies.Source(
                "FAKE_VERIFIED_PSP",
                "fixture-merchant",
                FinancialLedgerDependencies.Environment.SANDBOX,
                "payment-" + invoice,
                FinancialLedgerDependencies.Method.PSP),
            FinancialLedgerDependencies.Kind.PAYMENT,
            null,
            null,
            2000,
            FinancialLedgerDependencies.Currency.EUR,
            new FinancialLedgerDependencies.Occurrence(
                FinancialLedgerDependencies.Precision.EXACT, occurred, null, null),
            occurred,
            occurred,
            null,
            null,
            "fixture/payment-receipt",
            "ab".repeat(32));
    var verified =
        new PaymentSettlementDependencies.VerifiedPaymentFact(
            t, invoice, op, "fixture-v1", normalized);
    var deliveries = new java.util.concurrent.atomic.AtomicInteger();
    var actualDelivery =
        new GrpcInitialPaymentDelivery(
            ReactorTenantCreationLifecycleServiceGrpc.newReactorStub(rootChannel),
            billingSigner,
            CLOCK);
    var settlement =
        new PaymentSettlement(
            subscriptions,
            ledger,
            (db, tenantId, proof) -> Mono.justOrEmpty(proof.equals(evidenceId) ? verified : null),
            new GrpcTenantPaymentState(
                ReactorTenantPaymentStateServiceGrpc.newReactorStub(rootChannel),
                billingSigner,
                CLOCK),
            (tenantId, decision) -> {
              deliveries.incrementAndGet();
              return actualDelivery.deliver(tenantId, decision);
            },
            CLOSURE_CLOCK);
    int billingPort =
        server(
            "billing-service",
            interceptor(new BillingConfiguration(), billingSigner, "registrySecurity"),
            new PaymentSettlementGrpc(settlement));
    decisionStub.set(
        ReactorPaymentSettlementServiceGrpc.newReactorStub(channel("tenant-service", billingPort)));
    var rpc =
        PaymentSettlementServiceGrpc.newBlockingStub(channel("billing-service", billingPort))
            .withDeadlineAfter(15, TimeUnit.SECONDS);
    closureNow.set(
        java.time.Instant.parse(created.getTenant().getCreationDeadline()).plusSeconds(1));
    var expire =
        ExpireTenantCreationRequest.newBuilder()
            .setTenantId(t.toString())
            .setContext(
                ProvisioningAuthority.context(t, "tenant-service", "expireTenantCreation", CLOCK))
            .build();
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED,
        closureRpc
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                    serviceSignature(
                        expire,
                        TenantCreationLifecycleServiceGrpc.getExpireTenantCreationMethod()
                            .getFullMethodName(),
                        tenantTrust)))
            .expireTenantCreation(expire)
            .getLifecycle());
    for (int attempt = 0; attempt < 2; attempt++) {
      var context =
          ProvisioningAuthority.context(t, "billing-service", "applyVerifiedPayment", CLOCK)
              .toBuilder();
      context.setContext(
          context.getContext().toBuilder()
              .setIssuerService("billing-service")
              .addScopes("ledger:write"));
      var apply =
          ApplyVerifiedPaymentRequest.newBuilder()
              .setContext(context)
              .setTenantId(t.toString())
              .setEvidenceId(evidenceId.toString())
              .build();
      assertEquals(
          Status.Code.UNAUTHENTICATED,
          assertThrows(StatusRuntimeException.class, () -> rpc.applyVerifiedPayment(apply))
              .getStatus()
              .getCode());
      var paid =
          rpc.withInterceptors(
                  MetadataUtils.newAttachHeadersInterceptor(
                      serviceSignature(
                          apply,
                          PaymentSettlementServiceGrpc.getApplyVerifiedPaymentMethod()
                              .getFullMethodName(),
                          billingSigner)))
              .applyVerifiedPayment(apply);
      assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, paid.getOutcome());
      assertEquals(
          "ACTIVE",
          tenantDb
              .sql("SELECT lifecycle FROM taska.tenants WHERE id=:t")
              .bind("t", t)
              .map((r, m) -> r.get("lifecycle", String.class))
              .one()
              .block(WAIT));
    }
    for (String table :
        List.of(
            "payment_facts",
            "payment_allocations",
            "payment_settlements",
            "subscription_periods",
            "initial_payment_deliveries"))
      assertEquals(
          1L,
          billingDb
              .sql("SELECT count(*) n FROM taska." + table + " WHERE tenant_id=:t")
              .bind("t", t)
              .map((r, m) -> r.get("n", Long.class))
              .one()
              .block(WAIT),
          table);
    assertEquals(1, deliveries.get());
    assertEquals(
        occurred,
        billingDb
            .sql("SELECT starts_at FROM taska.subscription_periods WHERE tenant_id=:t")
            .bind("t", t)
            .map((r, m) -> r.get("starts_at", java.time.OffsetDateTime.class).toInstant())
            .one()
            .block(WAIT));
    closureNow.set(CLOCK.instant());
  }
}
