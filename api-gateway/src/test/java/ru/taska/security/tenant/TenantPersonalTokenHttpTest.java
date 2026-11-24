package ru.taska.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.config.GrpcClientConfig;
import ru.taska.controller.TenantPersonalTokenController;
import ru.taska.domain.*;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.service.TenantPersonalTokenClient;

/** HTTP/controller/client/protobuf mapping. Authentication and remote store have separate tests. */
class TenantPersonalTokenHttpTest {
  final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID(), session = UUID.randomUUID();
  final AtomicInteger writes = new AtomicInteger();
  final GatewayContextFactory contexts = mock(GatewayContextFactory.class);
  final RpcContextSecurity security = mock(RpcContextSecurity.class);
  Server server;
  ManagedChannel channel;
  WebTestClient http;
  final String secret = "tsk_pat_" + "x".repeat(43);

  TrustedTenantContext claims(CredentialKind kind) {
    return TrustedTenantContext.newBuilder().setActorUserId(actor.toString()).setTenantId(tenant.toString())
        .setCredentialKind(kind).setSessionId(session.toString()).addAllScopes(List.of("read", "write"))
        .setCredentialId(session.toString()).setActorGeneration("1").setMembershipGeneration("1").build();
  }

  void actor(CredentialKind kind) {
    var request = RequestContext.newBuilder().setHeader(Header.newBuilder().setRequestId("trace"))
        .setContext(claims(kind)).build();
    var verified = new VerifiedTenantContext(actor.toString(), tenant.toString(), "trace", "gatewayRequest",
        Set.of("read", "write"), request.toByteArray());
    when(contexts.buildContext(anyString(), any(), any())).thenReturn(Mono.just(new GatewayContext(
        "trace", "api-gateway", null, ActionChannel.UI, verified)));
    when(security.delegate(any(), eq("auth-service"), eq("createTenantPersonalToken"), any()))
        .thenReturn(request.toBuilder().setContext(claims(kind).toBuilder().setAction("createTenantPersonalToken")).build());
    when(security.sign(any(), any(), anyString(), any())).thenReturn(Mono.just("fixture-assertion"));
  }

  @BeforeEach
  void setup() throws Exception {
    server = ServerBuilder.forPort(0).addService(new ReactorTenantPersonalTokenServiceGrpc.TenantPersonalTokenServiceImplBase() {
      @Override public Mono<PersonalTokenCreated> createTenantPersonalToken(Mono<CreateTenantPersonalTokenRpcRequest> input) {
        return input.map(q -> {
          assertThat(q.getContext().getContext().getTenantId()).isEqualTo(tenant.toString());
          assertThat(q.getContext().getContext().getActorUserId()).isEqualTo(actor.toString());
          assertThat(q.getMutation().getIdempotencyKey()).isNotBlank();
          assertThat(q.getPayload().getName()).isEqualTo("CI");
          writes.incrementAndGet();
          return PersonalTokenCreated.newBuilder().setSecret(secret).setMetadata(PersonalToken.newBuilder()
              .setId(actor.toString()).setTenantId(tenant.toString()).setName("CI").setTokenPrefix("tsk_pat_xxxx")
              .addScopes(PersonalToken.ScopesValue.SCOPES_read).setCreatedAt(Instant.now().toString())
              .setExpiresAt(Instant.now().plusSeconds(3600).toString())).build();
        });
      }
    }).build().start();
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    var config = mock(GrpcClientConfig.class);
    when(config.authManagedChannel()).thenReturn(channel);
    var beans = new DefaultListableBeanFactory(); beans.registerSingleton("security", security);
    var client = new TenantPersonalTokenClient(config, beans.getBeanProvider(RpcContextSecurity.class));
    var executor = new GatewayRequestExecutor(new RequestIdProvider(), contexts);
    var errors = new GatewayErrorHandler(tools.jackson.databind.json.JsonMapper.builder().build(), new RestErrorMapper());
    org.springframework.test.util.ReflectionTestUtils.setField(errors, "trustedEnabled", true);
    // Install the production error handler into the HTTP test chain.
    http = WebTestClient.bindToController(new TenantPersonalTokenController(executor, client))
        .controllerAdvice(new GatewayValidationExceptionHandler(new RequestIdProvider()))
        .webFilter((exchange, chain) -> chain.filter(exchange).onErrorResume(e -> errors.handleError(exchange, e, "trace")))
        .build();
    actor(CredentialKind.CREDENTIAL_KIND_WEB_TENANT);
  }

  @AfterEach void close() { channel.shutdownNow(); server.shutdownNow(); }

  Map<String, Object> body(UUID target) { return Map.of("tenantId", target, "name", "CI", "scopes", List.of("read"), "expiresInDays", 30); }

  @Test void issuanceReturnsCompanyMetadataAndNoStore() {
    http.post().uri("/api/v1/account/tenant-tokens").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).bodyValue(body(tenant)).exchange().expectStatus().isCreated()
        .expectHeader().valueEquals("Cache-Control", "no-store").expectBody()
        .jsonPath("$.metadata.tenantId").isEqualTo(tenant.toString()).jsonPath("$.metadata.tokenPrefix").isEqualTo("tsk_pat_xxxx")
        .jsonPath("$.secret").isEqualTo(secret);
    assertThat(writes.get()).isEqualTo(1);
  }

  @Test void nonCanonicalAndDuplicateKeysNeverIssue() {
    for (String key : new String[]{"ABCDEF00-0000-4000-8000-000000000001", "1-1-1-1-1"})
      http.post().uri("/api/v1/account/tenant-tokens").header("Idempotency-Key", key)
          .bodyValue(body(tenant)).exchange().expectStatus().isBadRequest();
    http.post().uri("/api/v1/account/tenant-tokens")
        .header("Idempotency-Key", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        .bodyValue(body(tenant)).exchange().expectStatus().isBadRequest();
    assertThat(writes.get()).isZero();
  }

  @Test void wrongCompanyPatAndInvalidInputNeverIssue() {
    http.post().uri("/api/v1/account/tenant-tokens").header("Idempotency-Key", UUID.randomUUID().toString())
        .bodyValue(body(UUID.randomUUID())).exchange().expectStatus().isForbidden().expectBody()
        .jsonPath("$.code").isEqualTo("TENANT_MISMATCH").jsonPath("$.secret").doesNotExist();
    actor(CredentialKind.CREDENTIAL_KIND_USER_PAT);
    http.post().uri("/api/v1/account/tenant-tokens").header("Idempotency-Key", UUID.randomUUID().toString())
        .bodyValue(body(tenant)).exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("SESSION_REQUIRED");
    http.post().uri("/api/v1/account/tenant-tokens").bodyValue(body(tenant)).exchange().expectStatus().isBadRequest()
        .expectHeader().valueEquals("Cache-Control", "private, no-store");
    assertThat(writes.get()).isZero();
  }
}
