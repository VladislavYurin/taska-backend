package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.AuthSessionServiceGrpc;
import ru.taska.api.b2b.v1.CreateTenantSessionRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.RevokeWebSessionRpcRequest;
import ru.taska.api.b2b.v1.TenantSelectionRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.security.session.AuthSessionGrpcService;
import ru.taska.security.session.AuthSessionInterceptor;
import ru.taska.security.session.TenantAccessIssuer;
import ru.taska.security.session.WebSessionStore;
import ru.taska.service.AuthService;

/** Handler authority fixtures only; production mTLS/JWS is exercised in grpc-common-lib. */
class AuthSessionBoundaryTest {
  final UUID user = UUID.randomUUID(), web = UUID.randomUUID(), tenant = UUID.randomUUID();
  final WebSessionStore store = mock(WebSessionStore.class);
  final TenantAccessIssuer issuer = mock(TenantAccessIssuer.class);
  final AuthSessionGrpcService service =
      new AuthSessionGrpcService(mock(AuthService.class), store, issuer);

  TrustedTenantContext global() {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(user.toString())
        .setSessionId(web.toString())
        .setActorGeneration("1")
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
        .addScopes("tenants:select")
        .build();
  }

  <T> Mono<T> inbound(TrustedTenantContext claims, Supplier<Mono<T>> handler) throws Exception {
    var verified =
        new VerifiedTenantContext(
            claims.hasActorUserId() ? claims.getActorUserId() : null,
            claims.hasTenantId() ? claims.getTenantId() : null,
            UUID.randomUUID().toString(),
            claims.getAction(),
            Set.copyOf(claims.getScopesList()),
            RequestContext.newBuilder().setContext(claims).build().toByteArray());
    return Context.current().withValue(RpcContextBridge.INBOUND, verified).call(handler::get);
  }

  static void rejected(Status.Code code, Mono<?> operation) {
    StepVerifier.create(operation)
        .expectErrorMatches(
            error ->
                error instanceof StatusRuntimeException status
                    && status.getStatus().getCode() == code)
        .verify();
  }

  @Test
  void forgedRequestContextCannotReachSessionStore() {
    var mutation = MutationOptions.newBuilder().setIdempotencyKey(UUID.randomUUID().toString());
    var wire = RequestContext.newBuilder().setContext(global());
    rejected(
        Status.Code.UNAUTHENTICATED,
        service.createTenantSession(
            Mono.just(
                CreateTenantSessionRpcRequest.newBuilder()
                    .setContext(wire)
                    .setMutation(mutation)
                    .setPayload(TenantSelectionRequest.newBuilder().setTenantId(tenant.toString()))
                    .build())));
    rejected(
        Status.Code.UNAUTHENTICATED,
        service.revokeWebSession(
            Mono.just(
                RevokeWebSessionRpcRequest.newBuilder()
                    .setContext(wire)
                    .setMutation(mutation)
                    .build())));
    verifyNoInteractions(store, issuer);
  }

  @Test
  void onlyGlobalWebAuthorityCanSelectTenantOrLogoutParent() throws Exception {
    var selection =
        CreateTenantSessionRpcRequest.newBuilder()
            .setPayload(TenantSelectionRequest.newBuilder().setTenantId(tenant.toString()))
            .setMutation(
                MutationOptions.newBuilder().setIdempotencyKey(UUID.randomUUID().toString()))
            .build();
    for (var claims :
        java.util.List.of(
            global().toBuilder().setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT).build(),
            global().toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setTenantId(tenant.toString())
                .build(),
            global().toBuilder().clearScopes().build(),
            global().toBuilder().clearSessionId().build())) {
      rejected(
          Status.Code.PERMISSION_DENIED,
          inbound(claims, () -> service.createTenantSession(Mono.just(selection))));
      rejected(
          Status.Code.PERMISSION_DENIED,
          inbound(
              claims,
              () ->
                  service.revokeWebSession(
                      Mono.just(
                          RevokeWebSessionRpcRequest.newBuilder()
                              .setMutation(selection.getMutation())
                              .build()))));
    }
    verifyNoInteractions(store, issuer);
  }

  @Test
  void selectionAndLogoutUseVerifiedIdentityRatherThanRequestContext() throws Exception {
    UUID key = UUID.randomUUID(), session = UUID.randomUUID();
    Instant now = Instant.now();
    var issued =
        new WebSessionStore.Issued(
            web,
            session,
            user,
            tenant,
            3,
            UUID.randomUUID(),
            "test-refresh",
            now.plusSeconds(3600),
            1,
            now);
    when(store.openTenant(user, web, tenant, key)).thenReturn(Mono.just(issued));
    when(store.revokeWeb(user, web)).thenReturn(Mono.empty());
    when(issuer.issue(issued)).thenReturn("test-access");
    when(issuer.expiresIn(issued)).thenReturn(900L);
    var mutation = MutationOptions.newBuilder().setIdempotencyKey(key.toString());
    var forged =
        RequestContext.newBuilder()
            .setContext(
                global().toBuilder()
                    .setActorUserId(UUID.randomUUID().toString())
                    .setSessionId(UUID.randomUUID().toString()));
    var selected =
        inbound(
                global(),
                () ->
                    service.createTenantSession(
                        Mono.just(
                            CreateTenantSessionRpcRequest.newBuilder()
                                .setContext(forged)
                                .setMutation(mutation)
                                .setPayload(
                                    TenantSelectionRequest.newBuilder()
                                        .setTenantId(tenant.toString()))
                                .build())))
            .block();
    assertEquals(user.toString(), selected.getUserId());
    assertEquals(session.toString(), selected.getSessionId());
    assertNotNull(
        inbound(
                global(),
                () ->
                    service.revokeWebSession(
                        Mono.just(
                            RevokeWebSessionRpcRequest.newBuilder()
                                .setContext(forged)
                                .setMutation(mutation)
                                .build())))
            .block());
    verify(store).openTenant(user, web, tenant, key);
    verify(store).revokeWeb(user, web);
    verifyNoMoreInteractions(store);
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingTrustProviderFailsClosedForEveryProtectedRpc() {
    var factory = new DefaultListableBeanFactory();
    var guard =
        new AuthSessionInterceptor(factory.getBeanProvider(RpcContextSecurity.class), "test");
    for (var method :
        java.util.List.of(
            AuthSessionServiceGrpc.getCreateTenantSessionMethod(),
            AuthSessionServiceGrpc.getRevokeTenantSessionMethod(),
            AuthSessionServiceGrpc.getRevokeWebSessionMethod())) {
      ServerCall call = mock(ServerCall.class);
      ServerCallHandler next = mock(ServerCallHandler.class);
      when(call.getMethodDescriptor()).thenReturn(method);
      guard.interceptCall(call, new Metadata(), next);
      verify(call)
          .close(
              argThat(
                  status ->
                      status.getCode() == Status.Code.UNAVAILABLE
                          && "Auth session trust unavailable".equals(status.getDescription())),
              any(Metadata.class));
      verifyNoInteractions(next);
    }
  }
}
