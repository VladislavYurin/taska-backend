package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.ApproveTenantOAuthGrantRpcRequest;
import ru.taska.api.b2b.v1.AuthenticateTenantOAuthTokenRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.OAuthConsentRequest;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantOAuthGrantServiceGrpc;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityEvaluator;
import ru.taska.capability.CapabilityFacts;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.oauth.OAuthIntrospectionClient;
import ru.taska.security.oauth.TenantOAuthGrantGrpcService;
import ru.taska.security.oauth.TenantOAuthGrantInterceptor;
import ru.taska.security.oauth.TenantOAuthGrantStore;
import ru.taska.security.oauth.TenantOAuthTestSupport;

/** TAS-498 handler authority; production mTLS/JWS is verified in grpc-common-lib. */
class TenantOAuthGrantBoundaryTest {
  static final String CLIENT = "https://clients.example.test/client.json", STATE = "pending-state";
  final UUID user = UUID.randomUUID(), session = UUID.randomUUID(), tenant = UUID.randomUUID();
  final TenantOAuthGrantStore store = mock(TenantOAuthGrantStore.class);
  final OAuthIntrospectionClient introspection = TenantOAuthTestSupport.introspection();
  final AtomicReference<Mono<Long>> membership = new AtomicReference<>(Mono.just(2L));
  final TenantOAuthGrantGrpcService service =
      new TenantOAuthGrantGrpcService(store, (t, u) -> membership.get(), introspection);
  final String requestId = UUID.randomUUID().toString();

  TrustedTenantContext tenantSession() {
    return TrustedTenantContext.newBuilder()
        .setContextVersion(1)
        .setActorUserId(user.toString())
        .setTenantId(tenant.toString())
        .setSessionId(session.toString())
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
        .setActorGeneration("1")
        .setMembershipGeneration("2")
        .addScopes("read")
        .addScopes("write")
        .setAction("approveTenantOAuthGrant")
        .setExpiresAt(Instant.now().plusSeconds(30).toString())
        .build();
  }

  ApproveTenantOAuthGrantRpcRequest request(
      UUID key, boolean approved, OAuthConsentRequest.ScopesValue... scopes) {
    var payload =
        OAuthConsentRequest.newBuilder()
            .setClientId(CLIENT)
            .setTenantId(tenant.toString())
            .setAuthorizationRequestId(STATE)
            .setApproved(approved);
    for (var scope : scopes) payload.addScopes(scope);
    return ApproveTenantOAuthGrantRpcRequest.newBuilder()
        .setPayload(payload)
        .setMutation(MutationOptions.newBuilder().setIdempotencyKey(key.toString()))
        .build();
  }

  ApproveTenantOAuthGrantRpcRequest request() {
    return request(
        UUID.randomUUID(),
        true,
        OAuthConsentRequest.ScopesValue.SCOPES_read,
        OAuthConsentRequest.ScopesValue.SCOPES_write);
  }

  <T> Mono<T> inbound(TrustedTenantContext claims, Supplier<Mono<T>> handler) throws Exception {
    var verified =
        new VerifiedTenantContext(
            claims.hasActorUserId() ? claims.getActorUserId() : null,
            claims.hasTenantId() ? claims.getTenantId() : null,
            requestId,
            claims.getAction(),
            Set.copyOf(claims.getScopesList()),
            RequestContext.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(requestId))
                .setContext(claims)
                .build()
                .toByteArray());
    return Context.current().withValue(RpcContextBridge.INBOUND, verified).call(handler::get);
  }

  Mono<?> approve(TrustedTenantContext claims, ApproveTenantOAuthGrantRpcRequest request)
      throws Exception {
    return inbound(claims, () -> service.approveTenantOAuthGrant(Mono.just(request)));
  }

  static void rejected(Status.Code code, String reason, Mono<?> operation) {
    StepVerifier.create(operation)
        .expectErrorMatches(
            error ->
                error instanceof StatusRuntimeException status
                    && status.getStatus().getCode() == code
                    && (reason == null
                        || reason.equals(
                            GrpcErrorDetails.decode(status.getTrailers()).get("code"))))
        .verify();
  }

  @Test
  void forgedRequestContextCannotReachAuthorityOrStore() {
    var forged =
        request().toBuilder()
            .setContext(RequestContext.newBuilder().setContext(tenantSession()))
            .build();
    rejected(
        Status.Code.UNAUTHENTICATED, null, service.approveTenantOAuthGrant(Mono.just(forged)));
    verifyNoInteractions(store);
  }

  @Test
  void onlyTenantWebSessionOfTheSelectedTenantCanApprove() throws Exception {
    for (var kind :
        List.of(
            CredentialKind.CREDENTIAL_KIND_USER_PAT,
            CredentialKind.CREDENTIAL_KIND_USER_OAUTH,
            CredentialKind.CREDENTIAL_KIND_ADMIN_PAT,
            CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH,
            CredentialKind.CREDENTIAL_KIND_SERVICE))
      rejected(
          Status.Code.PERMISSION_DENIED,
          "SESSION_REQUIRED",
          approve(tenantSession().toBuilder().setCredentialKind(kind).build(), request()));
    // A social or password web login without a selected company is not enough.
    rejected(
        Status.Code.PERMISSION_DENIED,
        "SESSION_REQUIRED",
        approve(
            tenantSession().toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                .clearTenantId()
                .clearMembershipGeneration()
                .build(),
            request()));
    rejected(
        Status.Code.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        approve(
            tenantSession().toBuilder().setTenantId(UUID.randomUUID().toString()).build(),
            request()));
    rejected(
        Status.Code.PERMISSION_DENIED,
        "SCOPE_DENIED",
        approve(tenantSession().toBuilder().clearScopes().addScopes("read").build(), request()));
    verifyNoInteractions(store);
  }

  @Test
  void liveMembershipAtTheSessionGenerationIsRequired() throws Exception {
    membership.set(Mono.empty());
    rejected(Status.Code.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE", approve(tenantSession(), request()));
    membership.set(
        Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE")));
    rejected(Status.Code.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE", approve(tenantSession(), request()));
    membership.set(Mono.just(3L));
    rejected(Status.Code.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE", approve(tenantSession(), request()));
    membership.set(Mono.error(new IllegalStateException("tenant-service down")));
    rejected(Status.Code.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", approve(tenantSession(), request()));
    verifyNoInteractions(store);
  }

  @Test
  void refusalAndUnknownScopesNeverReachTheStore() throws Exception {
    rejected(
        Status.Code.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        approve(
            tenantSession(),
            request(UUID.randomUUID(), false, OAuthConsentRequest.ScopesValue.SCOPES_read)));
    rejected(
        Status.Code.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        approve(
            tenantSession(),
            request(UUID.randomUUID(), true, OAuthConsentRequest.ScopesValue.SCOPES_UNSPECIFIED)));
    verifyNoInteractions(store);
  }

  @Test
  void approvalUsesVerifiedSessionAndReturnsTheConsentContinuation() throws Exception {
    UUID key = UUID.randomUUID(), grant = UUID.randomUUID();
    when(store.approve(
            user, session, 2, tenant, CLIENT, List.of("read", "write"), "a+b=", key))
        .thenReturn(
            Mono.just(new TenantOAuthGrantStore.Approved(grant, tenant, List.of("read", "write"))));
    var request =
        request(
            key,
            true,
            OAuthConsentRequest.ScopesValue.SCOPES_read,
            OAuthConsentRequest.ScopesValue.SCOPES_write);
    request =
        request.toBuilder()
            .setPayload(request.getPayload().toBuilder().setAuthorizationRequestId("a+b="))
            .build();
    var result =
        (ru.taska.api.b2b.v1.OAuthConsentResult)
            approve(tenantSession(), request).block();
    assertEquals(grant.toString(), result.getGrantId());
    assertEquals(tenant.toString(), result.getTenantId());
    assertEquals("/consent?state=a%2Bb%3D&tenant_grant=" + grant, result.getRedirectPath());
  }

  @Test
  void ordinaryOAuthCannotChangeAccountOrIssueAPat() throws Exception {
    Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
    for (var flag : Flag.values()) flags.put(flag, true);
    CapabilityEvaluator evaluator =
        new CapabilityEvaluator(
            (verified, target, actions) -> {
              var none = new EnumMap<Action, Set<ErrorCode>>(Action.class);
              for (var action : actions) none.put(action, Set.of());
              return Mono.just(
                  new CapabilityFacts(
                      user,
                      target,
                      verified.requestId(),
                      Instant.now().plusSeconds(20),
                      new CapabilityFacts.Identity(
                          true, false, "1", true, "authorization", null, false, Set.of("read", "write")),
                      MembershipSnapshot.newBuilder()
                          .setTenantId(tenant.toString())
                          .setUserId(user.toString())
                          .setRole(Membership.RoleValue.ROLE_TENANT_ADMIN)
                          .setIsOwner(true)
                          .setState(Membership.StateValue.STATE_ACTIVE)
                          .setVersion("1")
                          .setTenantMembershipVersion("2")
                          .build(),
                      CapabilityFacts.ProjectRole.NONE,
                      Tenant.LifecycleValue.LIFECYCLE_ACTIVE,
                      true,
                      false,
                      null,
                      null,
                      CapabilityFacts.PlanKind.PAID,
                      Set.of(),
                      flags,
                      "1",
                      CapabilityFacts.Channel.MCP,
                      CapabilityFacts.PaymentChannel.NONE,
                      false,
                      none,
                      none,
                      null,
                      null));
            },
            Clock.systemUTC());
    // Owner and TENANT_ADMIN of the bound company still cannot use the integration for D221.
    var oauth =
        tenantSession().toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_OAUTH)
            .clearSessionId()
            .setCredentialId("authorization")
            .setGrantId(UUID.randomUUID().toString())
            .setGrantGeneration("1")
            .build();
    for (var action :
        List.of(
            Action.ACCOUNT_PROFILE_UPDATE,
            Action.ACCOUNT_AVATAR_WRITE,
            Action.ACCOUNT_PASSWORD_CHANGE,
            Action.ACCOUNT_EMAIL_CHANGE,
            Action.ACCOUNT_DELETE,
            Action.PAT_CREATE)) {
      var decision =
          inbound(
                  oauth,
                  () ->
                      RpcContextBridge.inbound(
                          evaluator.evaluate(
                              action, new CapabilityFacts.Target(tenant, null, user, null))))
              .block()
              .capability();
      assertEquals(false, decision.getAllowed(), action.toString());
      assertEquals(List.of(ErrorCode.ERROR_CODE_SESSION_REQUIRED), decision.getDenialCodesList());
    }
  }

  @Test
  void edgeAuthenticationExposesOnlyTheBoundTenantAndNeverMoreThanTheGrant() {
    UUID authorization = UUID.randomUUID(), grant = UUID.randomUUID();
    Instant tokenExpires = Instant.parse("2026-12-01T10:00:00Z"),
        grantExpires = Instant.parse("2026-11-30T10:00:00Z");
    TenantOAuthTestSupport.bound(
        introspection,
        "tsk_oat_bound",
        user,
        authorization,
        List.of("read", "write"),
        tokenExpires,
        grant,
        tenant,
        3,
        CLIENT);
    when(store.validate(grant, user, CLIENT, 3, tenant))
        .thenReturn(
            Mono.just(
                new TenantOAuthGrantStore.Binding(
                    grant, 3, user, tenant, List.of("read"), grantExpires, 7)));
    var binding =
        service
            .authenticateTenantOAuthToken(
                Mono.just(
                    AuthenticateTenantOAuthTokenRequest.newBuilder()
                        .setAccessToken("tsk_oat_bound")
                        .build()))
            .block();
    assertEquals(CredentialKind.CREDENTIAL_KIND_USER_OAUTH, binding.getCredentialKind());
    assertEquals(user.toString(), binding.getActorUserId());
    assertEquals(tenant.toString(), binding.getTenantId());
    assertEquals(authorization.toString(), binding.getCredentialId());
    assertEquals(grant.toString(), binding.getGrantId());
    assertEquals("3", binding.getGrantGeneration());
    assertEquals("7", binding.getActorGeneration());
    // A read grant never exposes the write scope a token might still carry.
    assertEquals(List.of("read"), binding.getScopesList());
    assertEquals(grantExpires.toString(), binding.getExpiresAt());
  }

  @Test
  void legacyRevokedOrForeignTokensDoNotAuthenticateAsATenantCredential() {
    TenantOAuthTestSupport.legacy(introspection, "tsk_oat_legacy", user, UUID.randomUUID());
    rejected(
        Status.Code.UNAUTHENTICATED,
        null,
        service.authenticateTenantOAuthToken(
            Mono.just(
                AuthenticateTenantOAuthTokenRequest.newBuilder()
                    .setAccessToken("tsk_oat_legacy")
                    .build())));
    UUID grant = UUID.randomUUID();
    TenantOAuthTestSupport.bound(
        introspection,
        "tsk_oat_revoked",
        user,
        UUID.randomUUID(),
        List.of("read"),
        Instant.now().plusSeconds(60),
        grant,
        tenant,
        1,
        CLIENT);
    when(store.validate(any(), any(), anyString(), anyLong(), any()))
        .thenReturn(
            Mono.error(
                new DomainException(
                    DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED", Map.of("code", "TOKEN_REVOKED"))));
    rejected(
        Status.Code.UNAUTHENTICATED,
        "TOKEN_REVOKED",
        service.authenticateTenantOAuthToken(
            Mono.just(
                AuthenticateTenantOAuthTokenRequest.newBuilder()
                    .setAccessToken("tsk_oat_revoked")
                    .build())));
    when(store.validate(any(), any(), anyString(), anyLong(), any()))
        .thenReturn(
            Mono.error(
                new DomainException(
                    DomainStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND",
                    Map.of("code", "RESOURCE_NOT_FOUND"))));
    rejected(
        Status.Code.UNAUTHENTICATED,
        null,
        service.authenticateTenantOAuthToken(
            Mono.just(
                AuthenticateTenantOAuthTokenRequest.newBuilder()
                    .setAccessToken("tsk_oat_revoked")
                    .build())));
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingTrustProviderFailsClosedForApprovalOnly() {
    var guard =
        new TenantOAuthGrantInterceptor(
            new DefaultListableBeanFactory().getBeanProvider(RpcContextSecurity.class), "test");
    ServerCall call = mock(ServerCall.class);
    ServerCallHandler next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor())
        .thenReturn(TenantOAuthGrantServiceGrpc.getApproveTenantOAuthGrantMethod());
    guard.interceptCall(call, new Metadata(), next);
    verify(call)
        .close(
            argThat(
                status ->
                    status.getCode() == Status.Code.UNAVAILABLE
                        && "Tenant OAuth trust unavailable".equals(status.getDescription())),
            any(Metadata.class));
    verifyNoInteractions(next);

    ServerCall edge = mock(ServerCall.class);
    when(edge.getMethodDescriptor())
        .thenReturn(TenantOAuthGrantServiceGrpc.getAuthenticateTenantOAuthTokenMethod());
    var headers = new Metadata();
    guard.interceptCall(edge, headers, next);
    verify(next).startCall(edge, headers);
  }
}
