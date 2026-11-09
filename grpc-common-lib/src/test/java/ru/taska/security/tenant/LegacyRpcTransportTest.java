package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.*;

@Timeout(60)
class LegacyRpcTransportTest {
  @TempDir Path dir;
  final String actor = UUID.randomUUID().toString(), tenant = UUID.randomUUID().toString();
  final AtomicBoolean active = new AtomicBoolean(true), available = new AtomicBoolean(true);
  final AtomicInteger calls = new AtomicInteger();
  RpcContextSecurity gateway, project;
  RpcContextSecurity.Keys keys;

  void certs() throws Exception {
    var helper = new TenantTransportTest();
    helper.certificates = dir;
    helper.openssl(
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
        "/CN=legacy-rpc-fixture");
    for (String name : List.of("api-gateway", "project-service")) {
      helper.openssl(
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
      helper.openssl(
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

  @BeforeEach
  void keys() throws Exception {
    var gen = KeyPairGenerator.getInstance("Ed25519");
    var pairs =
        Map.of("api-gateway", gen.generateKeyPair(), "project-service", gen.generateKeyPair());
    keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test", pairs.get(issuer).getPrivate());
          }

          public PublicKey verificationKey(String issuer, String kid) {
            return "test".equals(kid) && pairs.containsKey(issuer)
                ? pairs.get(issuer).getPublic()
                : null;
          }
        };
    Set<String> replay = ConcurrentHashMap.newKeySet();
    RpcContextSecurity.Authority authority =
        (c, r, p) ->
            available.get()
                ? Mono.just(
                    active.get()
                        && actor.equals(c.getContext().getActorUserId())
                        && "2".equals(c.getContext().getMembershipGeneration()))
                : Mono.error(new IllegalStateException("Primary unavailable"));
    gateway =
        new RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            keys,
            (i, j, e) -> Mono.just(replay.add(i + j)),
            authority);
    project =
        new RpcContextSecurity(
            "project-service",
            Clock.systemUTC(),
            keys,
            (i, j, e) -> Mono.just(replay.add(i + j)),
            authority);
  }

  VerifiedTenantContext root(String request, String company) {
    var now = Instant.now();
    var c =
        RequestContext.newBuilder()
            .setHeader(Header.newBuilder().setRequestId(request).setActionChannel("UI"))
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setContextVersion(1)
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                    .setActorUserId(actor)
                    .setTenantId(company)
                    .setSessionId(UUID.randomUUID().toString())
                    .setActorGeneration("1")
                    .setMembershipGeneration("2")
                    .addScopes("read")
                    .addScopes("write")
                    .setAction("gatewayRequest")
                    .setIssuerService("api-gateway")
                    .setAudienceService("api-gateway")
                    .setIssuedAt(now.toString())
                    .setExpiresAt(now.plusSeconds(30).toString())
                    .setAssertionId(UUID.randomUUID().toString()))
            .build();
    return gateway
        .authorize(c, "gatewayRequest", new RpcContextSecurity.Policy(true, true))
        .block();
  }

  CreateProjectRequest request(String id, String user) {
    return CreateProjectRequest.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(id).setNodeId("api-gateway"))
        .setBody(
            CreateProjectRequestBody.newBuilder()
                .setProjectKey("P")
                .setName("Project")
                .setUserId(user))
        .build();
  }

  @Test
  void signedLegacyCallUsesAutomaticFieldsAndRealVerifierAcrossSchedulerAndRevocation()
      throws Exception {
    certs();
    var handler =
        new ReactorProjectServiceGrpc.ProjectServiceImplBase() {
          @Override
          public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> input) {
            return input
                .publishOn(Schedulers.parallel())
                .flatMap(
                    r ->
                        VerifiedTenantContext.current()
                            .map(
                                v -> {
                                  calls.incrementAndGet();
                                  return ProjectResponse.newBuilder()
                                      .setId(v.tenantId())
                                      .setProjectKey(v.requestId())
                                      .build();
                                }))
                .transform(exception.GrpcExceptionHandler.withErrorHandling("createProject"));
          }
        };
    var server =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        dir.resolve("project-service.crt").toFile(),
                        dir.resolve("project-service.key").toFile())
                    .trustManager(dir.resolve("ca.crt").toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    handler, new LegacyRpcServerInterceptor("project-service", project, "test")))
            .build()
            .start();
    var factory =
        new FileMtlsRpcChannels(
            dir.resolve("ca.crt"),
            dir.resolve("api-gateway.crt"),
            dir.resolve("api-gateway.key"),
            "test");
    var channel =
        new InterceptedManagedChannel(
            factory.create("localhost", server.getPort(), "project-service"),
            new LegacyRpcClientInterceptor(gateway));
    try {
      var stub =
          ReactorProjectServiceGrpc.newReactorStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
      var id = UUID.randomUUID().toString();
      var parent = root(id, tenant);
      var call = RpcContextBridge.outbound(() -> stub.createProject(request(id, actor)));
      var result = RpcContextBridge.install(call, parent).block(Duration.ofSeconds(10));
      assertEquals(tenant, result.getId());
      assertEquals(id, result.getProjectKey());
      assertEquals(
          Status.Code.UNAUTHENTICATED,
          assertThrows(StatusRuntimeException.class, () -> call.block()).getStatus().getCode());
      var bad =
          stub.createProject(request(id, UUID.randomUUID().toString()))
              .transform(RpcContextBridge::outbound);
      assertEquals(
          Status.Code.PERMISSION_DENIED,
          assertThrows(
                  StatusRuntimeException.class, () -> RpcContextBridge.install(bad, parent).block())
              .getStatus()
              .getCode());
      active.set(false);
      assertEquals(
          Status.Code.PERMISSION_DENIED,
          assertThrows(
                  StatusRuntimeException.class,
                  () -> RpcContextBridge.install(call, parent).block())
              .getStatus()
              .getCode());
      active.set(true);
      available.set(false);
      assertEquals(
          Status.Code.UNAVAILABLE,
          assertThrows(
                  StatusRuntimeException.class,
                  () -> RpcContextBridge.install(call, parent).block())
              .getStatus()
              .getCode());
      assertEquals(1, calls.get());
      available.set(true);
      var all =
          Flux.range(0, 12)
              .flatMap(
                  n -> {
                    String company = n % 2 == 0 ? tenant : UUID.randomUUID().toString(),
                        rid = UUID.randomUUID().toString();
                    return RpcContextBridge.install(
                            stub.createProject(request(rid, actor))
                                .transform(RpcContextBridge::outbound),
                            root(rid, company))
                        .map(
                            r -> {
                              assertEquals(company, r.getId());
                              return r;
                            });
                  })
              .collectList()
              .block(Duration.ofSeconds(20));
      assertEquals(12, all.size());
      var wrong = factory.create("localhost", server.getPort(), "issue-service");
      try {
        assertThrows(
            StatusRuntimeException.class,
            () ->
                ProjectServiceGrpc.newBlockingStub(wrong)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .createProject(request(id, actor)));
      } finally {
        wrong.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void everyRegisteredRequestHasTypedContextAndExplicitPolicy() throws Exception {
    try (var input = getClass().getResourceAsStream("/security/rpc-route-policies.json")) {
      var routes =
          tools.jackson.databind.json.JsonMapper.builder().build().readTree(input).path("routes");
      assertTrue(routes.size() >= 131);
      var root = java.nio.file.Path.of("..");
      var owners = new java.util.HashMap<String, String>();
      for (String module :
          java.util.List.of(
              "project-service",
              "issue-service",
              "workflow-service",
              "auth-service",
              "admin-service",
              "notification-service")) {
        try (var files = java.nio.file.Files.walk(root.resolve(module + "/src/main/java"))) {
          for (var source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
            var match =
                java.util.regex.Pattern.compile("extends\\s+Reactor(\\w+)Grpc\\.")
                    .matcher(java.nio.file.Files.readString(source));
            while (match.find()) owners.put(match.group(1), module);
          }
        }
      }
      for (var r : routes) {
        String rpc = r.path("rpc").asText();
        String service = rpc.substring(0, rpc.indexOf('/'));
        service = service.substring(service.lastIndexOf('.') + 1);
        assertEquals(owners.get(service), r.path("audience").asText(), rpc);
        var clazz = Class.forName(r.path("requestClass").asText());
        var message =
            (com.google.protobuf.Message) clazz.getMethod("getDefaultInstance").invoke(null);
        var f = message.getDescriptorForType().findFieldByName("context");
        assertNotNull(f, r.path("rpc").asText());
        assertEquals(RequestContext.getDescriptor(), f.getMessageType());
        assertEquals(100, f.getNumber());
      }
    }
    assertEquals(1, CreateProjectRequest.getDescriptor().findFieldByName("header").getNumber());
    assertEquals(2, CreateProjectRequest.getDescriptor().findFieldByName("body").getNumber());
  }
}
