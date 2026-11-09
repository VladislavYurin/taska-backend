package ru.taska.transport.kafka;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.*;
import ru.taska.domain.entity.OutboxEvent;
import ru.taska.event.*;
import ru.taska.repository.*;
import ru.taska.security.tenant.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Real production RPC adapter/domain transaction/outbox publisher and consumer contract over Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@org.springframework.test.context.ActiveProfiles("trusted-rpc-owned-test")
@org.springframework.context.annotation.Import(TrustedIssueOutboxIntegrationTest.Trust.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class TrustedIssueOutboxIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

  @Container
  static final KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

  static final UUID actor = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
  static final AtomicBoolean active = new AtomicBoolean(true);
  static Path dir;
  static io.grpc.Server projectServer, workflowServer;
  static UUID projectA = UUID.randomUUID(),
      projectB = UUID.randomUUID(),
      generation = UUID.randomUUID();
  static AtomicBoolean workflowDown = new AtomicBoolean(false);

  static boolean owned(VerifiedTenantContext v, String project) {
    return (v.tenantId().equals(a.toString()) ? projectA : projectB).toString().equals(project);
  }

  static io.grpc.Server fixture(
      String service, RpcContextSecurity security, io.grpc.BindableService... handlers)
      throws Exception {
    var builder =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        dir.resolve(service + ".crt").toFile(),
                        dir.resolve(service + ".key").toFile())
                    .trustManager(dir.resolve("ca.crt").toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    for (var handler : handlers)
      builder.addService(
          ServerInterceptors.intercept(
              handler, new LegacyRpcServerInterceptor(service, security, "test")));
    return builder.build().start();
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (projectServer != null) projectServer.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    if (workflowServer != null) workflowServer.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    if (dir != null)
      try (var files = Files.walk(dir)) {
        for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.deleteIfExists(file);
      }
  }

  static RpcContextSecurity gateway, receiver;
  static String runtimePassword = UUID.randomUUID().toString();

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("trusted-rpc-owned-test")
  static class Trust {
    @org.springframework.context.annotation.Bean
    TaskaEventRegistry consumerContract(
        @org.springframework.beans.factory.annotation.Value("${kafka.topics.issue-events}")
            String issue,
        @org.springframework.beans.factory.annotation.Value("${kafka.topics.project-events}")
            String project,
        @org.springframework.beans.factory.annotation.Value("${kafka.topics.user-events}")
            String user) {
      return new TaskaEventRegistry(issue, project, user);
    }

    @org.springframework.context.annotation.Bean
    RpcContextSecurity rpcContextSecurity() {
      return receiver;
    }
  }

  @org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort int grpcPort;
  static final String topic = "issue.native." + UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(30);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    try {
      dir = Files.createTempDirectory("tas316-project-tls-");
      certs();
      var gen = KeyPairGenerator.getInstance("Ed25519");
      var pairs = new java.util.HashMap<String, KeyPair>();
      for (String name :
          List.of("api-gateway", "issue-service", "project-service", "workflow-service"))
        pairs.put(name, gen.generateKeyPair());
      var keys =
          new RpcContextSecurity.Keys() {
            public RpcContextSecurity.SigningKey signingKey(String issuer) {
              return new RpcContextSecurity.SigningKey("fixture", pairs.get(issuer).getPrivate());
            }

            public PublicKey verificationKey(String issuer, String kid) {
              return pairs.containsKey(issuer) && kid.equals("fixture")
                  ? pairs.get(issuer).getPublic()
                  : null;
            }
          };
      Set<String> consumed = ConcurrentHashMap.newKeySet();
      RpcContextSecurity.ReplayGuard replay = (i, j, e) -> Mono.just(consumed.add(i + j));
      RpcContextSecurity.Authority authority =
          (c, rpc, policy) ->
              Mono.just(
                  active.get()
                      && actor.toString().equals(c.getContext().getActorUserId())
                      && Set.of(a.toString(), b.toString()).contains(c.getContext().getTenantId()));
      gateway = new RpcContextSecurity("api-gateway", Clock.systemUTC(), keys, replay, authority);
      receiver =
          new RpcContextSecurity("issue-service", Clock.systemUTC(), keys, replay, authority);
      projectServer =
          fixture(
              "project-service",
              new RpcContextSecurity("project-service", Clock.systemUTC(), keys, replay, authority),
              new ReactorProjectServiceGrpc.ProjectServiceImplBase() {
                public Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole(
                    Mono<CheckProjectMemberRoleRequest> requests) {
                  return RpcContextBridge.inbound(
                      requests.flatMap(
                          r ->
                              VerifiedTenantContext.current()
                                  .map(
                                      v ->
                                          CheckProjectMemberRoleResponse.newBuilder()
                                              .setProjectExists(
                                                  owned(v, r.getBody().getProjectId()))
                                              .setIsMember(true)
                                              .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                                              .setProjectArchived(false)
                                              .build())));
                }

                public Mono<ProjectKeyResponse> getProjectKeyInternal(
                    Mono<GetProjectKeyInternalRequest> requests) {
                  return RpcContextBridge.inbound(
                      requests.flatMap(
                          r ->
                              VerifiedTenantContext.current()
                                  .flatMap(
                                      v ->
                                          owned(v, r.getBody().getProjectId())
                                              ? Mono.just(
                                                  ProjectKeyResponse.newBuilder()
                                                      .setProjectKey("SAME")
                                                      .build())
                                              : Mono.error(
                                                  Status.NOT_FOUND.asRuntimeException()))));
                }

                public Mono<ListProjectIssueTypesResponse> listProjectIssueTypes(
                    Mono<ProjectIssueTypesRequest> requests) {
                  return RpcContextBridge.inbound(
                      requests.flatMap(
                          r ->
                              VerifiedTenantContext.current()
                                  .flatMap(
                                      v ->
                                          owned(v, r.getProjectId())
                                              ? Mono.just(
                                                  ListProjectIssueTypesResponse.newBuilder()
                                                      .addItems(
                                                          ProjectIssueTypeResponse.newBuilder()
                                                              .setCode("TASK")
                                                              .setGeneration(generation.toString()))
                                                      .build())
                                              : Mono.error(
                                                  Status.NOT_FOUND.asRuntimeException()))));
                }
              });
      var wfSecurity =
          new RpcContextSecurity("workflow-service", Clock.systemUTC(), keys, replay, authority);
      workflowServer =
          fixture(
              "workflow-service",
              wfSecurity,
              new ReactorWorkflowServiceGrpc.WorkflowServiceImplBase() {
                public Mono<com.google.protobuf.Empty> checkProjectWorkflowScope(
                    Mono<CheckProjectWorkflowScopeRequest> requests) {
                  return RpcContextBridge.inbound(
                      requests.flatMap(
                          r ->
                              VerifiedTenantContext.current()
                                  .flatMap(
                                      v ->
                                          workflowDown.get()
                                              ? Mono.error(Status.UNAVAILABLE.asRuntimeException())
                                              : owned(v, r.getProjectId())
                                                  ? Mono.just(
                                                      com.google.protobuf.Empty
                                                          .getDefaultInstance())
                                                  : Mono.error(
                                                      Status.NOT_FOUND.asRuntimeException()))));
                }
              },
              new ReactorWorkflowBindingJournalGrpc.WorkflowBindingJournalImplBase() {
                public Mono<WorkflowBindingIntent> getPending(
                    Mono<TypeOperationProjectRequest> requests) {
                  return RpcContextBridge.inbound(
                      requests.flatMap(
                          r ->
                              VerifiedTenantContext.current()
                                  .map(
                                      v -> {
                                        assertTrue(owned(v, r.getProjectId()));
                                        assertNotNull(io.grpc.Context.current().getDeadline());
                                        return WorkflowBindingIntent.getDefaultInstance();
                                      })));
                }
              });
      try (var c =
              java.sql.DriverManager.getConnection(
                  pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("CREATE SCHEMA taska");
        st.execute("CREATE ROLE rpc_app LOGIN NOINHERIT PASSWORD '" + runtimePassword + "'");
        st.execute("ALTER ROLE rpc_app SET app.rpc_scope_enforced='true'");
        st.execute("GRANT USAGE ON SCHEMA taska TO rpc_app");
        st.execute(
            "ALTER DEFAULT PRIVILEGES IN SCHEMA taska GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES"
                + " TO rpc_app");
      }
    } catch (Exception e) {
      throw new IllegalStateException("Owned transport fixture initialization failed", e);
    }
    r.add("grpc.client.project-service.host", () -> "localhost");
    r.add("grpc.client.project-service.port", () -> projectServer.getPort());
    r.add("grpc.client.workflow-service.host", () -> "localhost");
    r.add("grpc.client.workflow-service.port", () -> workflowServer.getPort());
    r.add("taska.rpc.context.enabled", () -> true);
    r.add("DEPLOY_ENVIRONMENT", () -> "test");
    r.add("taska.rpc.tls.ca-certificate", () -> dir.resolve("ca.crt").toString());
    r.add("taska.rpc.tls.certificate", () -> dir.resolve("issue-service.crt").toString());
    r.add("taska.rpc.tls.private-key", () -> dir.resolve("issue-service.key").toString());
    r.add("spring.grpc.server.ssl.enabled", () -> true);
    r.add("spring.grpc.server.ssl.client-auth", () -> "REQUIRE");
    r.add("spring.grpc.server.ssl.bundle", () -> "rpc");
    r.add(
        "spring.ssl.bundle.pem.rpc.keystore.certificate",
        () -> dir.resolve("issue-service.crt").toUri().toString());
    r.add(
        "spring.ssl.bundle.pem.rpc.keystore.private-key",
        () -> dir.resolve("issue-service.key").toUri().toString());
    r.add(
        "spring.ssl.bundle.pem.rpc.truststore.certificate",
        () -> dir.resolve("ca.crt").toUri().toString());
    r.add("spring.liquibase.url", pg::getJdbcUrl);
    r.add("spring.liquibase.user", pg::getUsername);
    r.add("spring.liquibase.password", pg::getPassword);
    r.add(
        "spring.r2dbc.url",
        () ->
            "r2dbc:postgresql://"
                + pg.getHost()
                + ":"
                + pg.getMappedPort(5432)
                + "/"
                + pg.getDatabaseName());
    r.add("spring.r2dbc.username", () -> "rpc_app");
    r.add("spring.r2dbc.password", () -> runtimePassword);
    r.add("spring.grpc.server.address", () -> "127.0.0.1:0");
    r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    r.add("app.kafka.topics.issue-events", () -> topic);
    r.add("kafka.topics.issue-events", () -> topic);
    r.add("kafka.topics.project-events", () -> topic + ".project");
    r.add("kafka.topics.user-events", () -> topic + ".user");
  }

  @MockitoBean OutboxEventScheduler scheduler;
  @Autowired IssueRepository issues;
  @Autowired OutboxEventRepository outbox;
  @Autowired OutboxEventPublisher publisher;
  @Autowired ObjectMapper json;
  @Autowired TaskaEventRegistry consumerContract;

  static void openssl(String... args) throws Exception {
    var command = new ArrayList<>(List.of("openssl"));
    command.addAll(List.of(args));
    var p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    var output = p.getInputStream().readAllBytes();
    assertTrue(p.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, p.exitValue(), new String(output));
  }

  static void certs() throws Exception {
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
        "/CN=owned-outbox-fixture");
    for (String name :
        List.of("api-gateway", "project-service", "issue-service", "workflow-service")) {
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
          "/CN=localhost");
      Files.writeString(
          dir.resolve(name + ".ext"),
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
  }

  RequestContext root(UUID tenant, String request) {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(request).setActionChannel("UI"))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setContextVersion(1)
                .setActorUserId(actor.toString())
                .setTenantId(tenant.toString())
                .setSessionId(UUID.randomUUID().toString())
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setActorGeneration("1")
                .setMembershipGeneration("1")
                .addScopes("read")
                .addScopes("write")
                .setAction("gatewayRequest")
                .setIssuerService("api-gateway")
                .setAudienceService("api-gateway")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }

  long ownerCount(String table) throws Exception {
    try (var c =
            java.sql.DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM taska." + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  <T> T call(UUID tenant, String trace, java.util.function.Supplier<Mono<T>> send) {
    var principal =
        gateway
            .authorize(
                root(tenant, trace), "gatewayRequest", new RpcContextSecurity.Policy(true, true))
            .block(WAIT);
    return RpcContextBridge.install(RpcContextBridge.outbound(send), principal).block(WAIT);
  }

  CreateIssueRequest create(UUID project, String trace, String key) {
    return CreateIssueRequest.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(trace).setNodeId("api-gateway"))
        .setBody(
            CreateIssueRequestBody.newBuilder()
                .setIdempotencyKey(key)
                .setProjectId(project.toString())
                .setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK)
                .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM)
                .setSummary("Owned native issue")
                .setReporterId(actor.toString()))
        .build();
  }

  @Test
  void nativeNestedWritesAreScopedAndFailuresLeaveNoPartialState() throws Exception {
    var channel =
        new InterceptedManagedChannel(
            new FileMtlsRpcChannels(
                    dir.resolve("ca.crt"),
                    dir.resolve("api-gateway.crt"),
                    dir.resolve("api-gateway.key"),
                    "test")
                .create("localhost", grpcPort, "issue-service"),
            new LegacyRpcClientInterceptor(gateway));
    try {
      var stub =
          ReactorIssueServiceGrpc.newReactorStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
      var records = new ArrayList<OutboxEvent>();
      String idempotency = UUID.randomUUID().toString();
      for (UUID company : List.of(a, b)) {
        var project = company.equals(a) ? projectA : projectB;
        String trace = UUID.randomUUID().toString();
        var request = create(project, trace, idempotency);
        var result = call(company, trace, () -> stub.createIssue(request));
        assertEquals("SAME-1", result.getIssueKey());
        var retry = call(company, trace, () -> stub.createIssue(request));
        assertEquals(result.getId(), retry.getId());
        var event =
            outbox
                .findAll()
                .filter(
                    e ->
                        e.getAggregateId().toString().equals(result.getId())
                            && e.getEventType().equals(EventType.ISSUE_CREATED.getValue()))
                .single()
                .block(WAIT);
        assertEquals(company, event.getTenantId());
        assertEquals(trace, event.getRequestId());
        records.add(event);
        assertEquals(0, issues.count().block(WAIT));
      }
      assertEquals(2, ownerCount("issues"));
      assertEquals(2, ownerCount("idempotency_keys"));
      long events = outbox.count().block(WAIT);
      String trace = UUID.randomUUID().toString();
      var foreign = create(projectB, trace, UUID.randomUUID().toString());
      var failure =
          assertThrows(
              StatusRuntimeException.class, () -> call(a, trace, () -> stub.createIssue(foreign)));
      assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
      workflowDown.set(true);
      String trace2 = UUID.randomUUID().toString();
      var unavailable = create(projectA, trace2, UUID.randomUUID().toString());
      assertThrows(
          StatusRuntimeException.class, () -> call(a, trace2, () -> stub.createIssue(unavailable)));
      assertEquals(2, ownerCount("issues"));
      assertEquals(2, ownerCount("idempotency_keys"));
      assertEquals(events, outbox.count().block(WAIT));
      workflowDown.set(false);
      // Fail after domain INSERT, so the real transaction must roll back counter/history/outbox
      // too.
      try (var c =
              java.sql.DriverManager.getConnection(
                  pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute(
            "ALTER TABLE taska.outbox_events ADD CONSTRAINT owned_failure CHECK"
                + " (aggregate_type<>'issue') NOT VALID");
      }
      String trace3 = UUID.randomUUID().toString();
      var rollback = create(projectA, trace3, UUID.randomUUID().toString());
      assertThrows(
          StatusRuntimeException.class, () -> call(a, trace3, () -> stub.createIssue(rollback)));
      assertEquals(2, ownerCount("issues"));
      assertEquals(2, ownerCount("idempotency_keys"));
      assertEquals(events, outbox.count().block(WAIT));
      assertEquals(2, ownerCount("project_counters"));
      var props = new Properties();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
      props.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(
          ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
          "org.apache.kafka.common.serialization.StringDeserializer");
      props.put(
          ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
          "org.apache.kafka.common.serialization.StringDeserializer");
      try (var consumer = new KafkaConsumer<String, String>(props)) {
        consumer.subscribe(List.of(topic));
        for (var event : records) publisher.publish(event).block(WAIT);
        publisher.publish(records.getFirst()).block(WAIT);
        var received = new ArrayList<ConsumerRecord<String, String>>();
        var until = Instant.now().plusSeconds(30);
        while (received.size() < 3 && Instant.now().isBefore(until))
          consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        assertEquals(3, received.size());
        Set<UUID> ids = new HashSet<>(), companies = new HashSet<>();
        for (var r : received) {
          var event = consumerContract.validate(topic, json.readValue(r.value(), TaskaEvent.class));
          assertEquals(TaskaEventContract.partitionKey(event), r.key());
          ids.add(event.id());
          companies.add(event.tenantId());
        }
        assertEquals(2, ids.size());
        assertEquals(Set.of(a, b), companies);
      }

    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
