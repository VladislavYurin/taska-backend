package ru.taska.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.*;
import ru.taska.domain.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.security.tenant.*;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

class TrustedGatewayContextTest {
  final String actor = UUID.randomUUID().toString(), tenant = UUID.randomUUID().toString();
  final Fixture fixture = new Fixture();
  GatewayContextFactory factory;
  final ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub stub =
      mock(
          ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub.class,
          RETURNS_SELF);

  static class Fixture {
    GatewayCredential reply;
    boolean allowed = true;
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.reply =
        GatewayCredential.newBuilder()
            .setUser(UserContext.newBuilder().setUserId(actor).addScopes("read").addScopes("write"))
            .setBinding(
                TrustedTenantContext.newBuilder()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                    .setActorUserId(actor)
                    .setTenantId(tenant)
                    .setSessionId(UUID.randomUUID().toString())
                    .setActorGeneration("1")
                    .setMembershipGeneration("2")
                    .addScopes("read")
                    .addScopes("write"))
            .build();
    when(stub.resolveGatewayCredential(any(ResolveGatewayCredentialRequest.class)))
        .thenAnswer(i -> Mono.just(fixture.reply));
    var mapper = mock(ContextMapper.class);
    when(mapper.mapToGatewayUserContext(any()))
        .thenReturn(
            new GatewayUserContext(
                actor,
                "owner@example.invalid",
                "Owner",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER,
                GatewayTokenKind.JWT,
                null,
                List.of("read", "write")));
    factory =
        new GatewayContextFactory(
            new BearerTokenExtractor(), mock(GrpcAuthServiceClient.class), mapper);
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String i) {
            return new RpcContextSecurity.SigningKey("test", pair.getPrivate());
          }

          public PublicKey verificationKey(String i, String k) {
            return pair.getPublic();
          }
        };
    var trust =
        new RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            keys,
            (i, j, e) -> Mono.just(true),
            (c, r, p) -> Mono.just(fixture.allowed));
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("trust", trust);
    beans.registerSingleton("stub", stub);
    ReflectionTestUtils.setField(
        factory, "trustedSecurity", beans.getBeanProvider(RpcContextSecurity.class));
    ReflectionTestUtils.setField(
        factory,
        "credentialStubs",
        beans.getBeanProvider(
            ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub.class));
    ReflectionTestUtils.setField(factory, "trustedEnabled", true);
    ReflectionTestUtils.setField(factory, "nodeId", "api-gateway");
  }

  MockServerWebExchange exchange(String path, String... company) {
    var request =
        MockServerHttpRequest.get(path).header("Authorization", "Bearer credential-fixture");
    if (company.length > 0) request.header("X-Tenant-Id", company);
    return MockServerWebExchange.from(request);
  }

  @Test
  void authenticatedBindingEntersReactiveRequestAndDoesNotLeak() {
    var id = UUID.randomUUID().toString();
    var ex = exchange("/api/v1/projects", tenant);
    ex.getRequest().mutate();
    var executor = new GatewayRequestExecutor(new RequestIdProvider(), factory);
    String result =
        executor
            .execute(
                ex,
                EndpointSecurity.PROTECTED,
                c ->
                    VerifiedTenantContext.current()
                        .map(
                            v -> {
                              assertEquals(actor, v.actorId());
                              assertEquals(tenant, v.tenantId());
                              assertEquals(ActionChannel.UI, c.channel());
                              assertEquals(c.requestId(), v.requestId());
                              return v.tenantId();
                            }))
            .block();
    assertEquals(tenant, result);
    assertThrows(IllegalStateException.class, () -> VerifiedTenantContext.current().block());
  }

  @Test
  void headerCannotSelectDifferentTenant() {
    assertEquals(
        io.grpc.Status.Code.PERMISSION_DENIED,
        assertThrows(
                io.grpc.StatusRuntimeException.class,
                () ->
                    factory
                        .buildContext(
                            UUID.randomUUID().toString(),
                            exchange("/api/v1/projects", UUID.randomUUID().toString()),
                            EndpointSecurity.PROTECTED)
                        .block())
            .getStatus()
            .getCode());
  }

  @Test
  void conflictingTenantHeadersAreRejected() {
    assertThrows(
        io.grpc.StatusRuntimeException.class,
        () ->
            factory
                .buildContext(
                    UUID.randomUUID().toString(),
                    exchange("/api/v1/projects", tenant, UUID.randomUUID().toString()),
                    EndpointSecurity.PROTECTED)
                .block());
  }

  @Test
  void urlCannotOverrideBinding() {
    assertThrows(
        io.grpc.StatusRuntimeException.class,
        () ->
            factory
                .buildContext(
                    UUID.randomUUID().toString(),
                    exchange("/api/v1/tenants/" + UUID.randomUUID()),
                    EndpointSecurity.PROTECTED)
                .block());
  }

  @Test
  void canonicalTenantTargetsMatchWithoutTreatingNestedPathsAsTenantRoutes() {
    assertNotNull(
        factory
            .buildContext(
                UUID.randomUUID().toString(),
                exchange(
                    "/api/v1/tenants/" + tenant.toUpperCase(Locale.ROOT),
                    tenant.toUpperCase(Locale.ROOT)),
                EndpointSecurity.PROTECTED)
            .block());
    assertNotNull(
        factory
            .buildContext(
                UUID.randomUUID().toString(),
                exchange("/api/v1/projects/example/companies/" + UUID.randomUUID()),
                EndpointSecurity.PROTECTED)
            .block());
    fixture.reply =
        fixture.reply.toBuilder()
            .setBinding(
                fixture.reply.getBinding().toBuilder()
                    .clearTenantId()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL))
            .build();
    var global =
        factory
            .buildContext(
                UUID.randomUUID().toString(),
                exchange("/api/v1/tenants/" + tenant + "/sessions"),
                EndpointSecurity.PROTECTED)
            .block();
    assertNull(global.verified().tenantId());
  }

  @Test
  void globalSessionCannotBecomeTenantFromHeader() {
    fixture.reply =
        fixture.reply.toBuilder()
            .setBinding(
                fixture.reply.getBinding().toBuilder()
                    .clearTenantId()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL))
            .build();
    assertThrows(
        io.grpc.StatusRuntimeException.class,
        () ->
            factory
                .buildContext(
                    UUID.randomUUID().toString(),
                    exchange("/api/v1/projects", tenant),
                    EndpointSecurity.PROTECTED)
                .block());
  }

  @Test
  void unavailableOrDeniedAuthorityDoesNotInstallAnyContext() {
    fixture.allowed = false;
    assertEquals(
        io.grpc.Status.Code.PERMISSION_DENIED,
        assertThrows(
                io.grpc.StatusRuntimeException.class,
                () ->
                    factory
                        .buildContext(
                            UUID.randomUUID().toString(),
                            exchange("/api/v1/projects"),
                            EndpointSecurity.PROTECTED)
                        .block())
            .getStatus()
            .getCode());
    assertThrows(IllegalStateException.class, () -> VerifiedTenantContext.current().block());
  }

  @Test
  void publicAuthHasSeparatePolicyAndNeverRequiresCredentialResolution() {
    factory
        .buildContext(
            UUID.randomUUID().toString(),
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/methods")),
            EndpointSecurity.PUBLIC)
        .block();
    verify(stub, never()).resolveGatewayCredential(any(ResolveGatewayCredentialRequest.class));
  }
}
