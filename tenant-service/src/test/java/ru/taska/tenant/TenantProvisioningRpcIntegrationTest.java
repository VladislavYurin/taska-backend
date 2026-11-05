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
            return List.of("gateway", "tenant-service").contains(issuer) && key.equals("fixture")
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
                context.getContext().getIssuerService().equals("tenant-service")
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
    int tenantPort =
        server(
            "tenant-service",
            interceptor(new TenantConfiguration(), tenantTrust, "registrySecurity"),
            new TenantGrpcService(
                registry, org.mockito.Mockito.mock(TenantMembershipService.class)),
            new TenantProvisioningRpc(saga));
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
  void signedCreationResumesAcrossLostRemoteAcksAndReadsFinalOperation() {
    for (UUID plan : List.of(FREE, PAID)) {
      String key = UUID.randomUUID().toString();
      var base =
          CreateTenantRpcRequest.newBuilder()
              .setPayload(
                  ru.taska.api.b2b.v1.CreateTenantRequest.newBuilder()
                      .setName("RPC fixture")
                      .setPlanVersionId(plan.toString()))
              .setMutation(ru.taska.api.b2b.v1.MutationOptions.newBuilder().setIdempotencyKey(key));
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
      for (int i = 0; i < 3; i++) {
        worker.poll().block(WAIT);
        tenantDb
            .sql(
                "UPDATE taska.tenant_provisioning_dispatch SET next_attempt_at=now()-interval '1"
                    + " second' WHERE completed_at IS NULL")
            .then()
            .block(WAIT);
      }
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
  }

  @AfterAll
  static void cleanup() throws Exception {
    for (var channel : channels) channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    for (var server : servers) server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    for (var pool : pools) pool.disposeLater().block(WAIT);
  }
}
