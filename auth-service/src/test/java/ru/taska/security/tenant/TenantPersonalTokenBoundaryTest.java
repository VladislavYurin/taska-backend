package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.AuthenticateTenantPersonalTokenRequest;
import ru.taska.api.b2b.v1.CreateTenantPersonalTokenRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PersonalToken;
import ru.taska.api.b2b.v1.PersonalTokenRequest;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantPersonalTokenServiceGrpc;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityEvaluator;
import ru.taska.capability.CapabilityFacts;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.capability.CapabilityFacts.PlanKind;
import ru.taska.security.pat.TenantPersonalTokenAdmission;
import ru.taska.security.pat.TenantPersonalTokenGrpcService;
import ru.taska.security.pat.TenantPersonalTokenInterceptor;
import ru.taska.security.pat.TenantPersonalTokenStore;

/** Handler authority with the real pat.create policy; production mTLS/JWS is in grpc-common-lib. */
class TenantPersonalTokenBoundaryTest {
  final UUID user = UUID.randomUUID(), session = UUID.randomUUID(), tenant = UUID.randomUUID();
  final TenantPersonalTokenStore store = mock(TenantPersonalTokenStore.class);
  final AtomicInteger reads = new AtomicInteger();
  final Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
  PlanKind plan = PlanKind.PAID;
  Membership.StateValue membership = Membership.StateValue.STATE_ACTIVE;
  boolean factsAvailable = true;
  final String credential = UUID.randomUUID().toString();
  final CapabilityEvaluator evaluator = new CapabilityEvaluator(this::facts, Clock.systemUTC());
  final TenantPersonalTokenGrpcService service =
      new TenantPersonalTokenGrpcService(store, new TenantPersonalTokenAdmission(evaluator));

  {
    for (var flag : Flag.values()) flags.put(flag, true);
    flags.put(Flag.FREE_PERSONAL_API_TOKENS, false);
  }

  Mono<CapabilityFacts> facts(
      VerifiedTenantContext verified, CapabilityFacts.Target target, Set<Action> actions) {
    reads.incrementAndGet();
    if (!factsAvailable) return Mono.empty();
    var none = new EnumMap<Action, Set<ErrorCode>>(Action.class);
    for (var action : actions) none.put(action, Set.of());
    return Mono.just(
        new CapabilityFacts(
            user,
            target,
            verified.requestId(),
            Instant.now().plusSeconds(20),
            new CapabilityFacts.Identity(
                true,
                false,
                "1",
                true,
                credential,
                session.toString(),
                false,
                Set.of("read", "write")),
            MembershipSnapshot.newBuilder()
                .setTenantId(tenant.toString())
                .setUserId(user.toString())
                .setRole(Membership.RoleValue.ROLE_MEMBER)
                .setState(membership)
                .setVersion("1")
                .setTenantMembershipVersion("2")
                .build(),
            CapabilityFacts.ProjectRole.NONE,
            Tenant.LifecycleValue.LIFECYCLE_ACTIVE,
            true,
            false,
            null,
            null,
            plan,
            Set.of(),
            flags,
            "1",
            CapabilityFacts.Channel.DIRECT,
            CapabilityFacts.PaymentChannel.NONE,
            false,
            none,
            none,
            null,
            null));
  }

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
        .setAction("createTenantPersonalToken")
        .setExpiresAt(Instant.now().plusSeconds(30).toString())
        .build();
  }

  CreateTenantPersonalTokenRpcRequest request(UUID key, PersonalTokenRequest.ScopesValue... scopes) {
    var payload =
        PersonalTokenRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setName("CI bot")
            .setExpiresInDays(30);
    for (var scope : scopes) payload.addScopes(scope);
    return CreateTenantPersonalTokenRpcRequest.newBuilder()
        .setPayload(payload)
        .setMutation(MutationOptions.newBuilder().setIdempotencyKey(key.toString()))
        .build();
  }

  final String requestId = UUID.randomUUID().toString();

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

  Mono<?> create(TrustedTenantContext claims, CreateTenantPersonalTokenRpcRequest request)
      throws Exception {
    return inbound(claims, () -> service.createTenantPersonalToken(Mono.just(request)));
  }

  @Test
  void forgedRequestContextCannotReachPolicyOrStore() {
    var forged =
        request(UUID.randomUUID(), PersonalTokenRequest.ScopesValue.SCOPES_read).toBuilder()
            .setContext(RequestContext.newBuilder().setContext(tenantSession()))
            .build();
    rejected(
        Status.Code.UNAUTHENTICATED, null, service.createTenantPersonalToken(Mono.just(forged)));
    assertEquals(0, reads.get());
    verifyNoInteractions(store);
  }

  @Test
  void onlyTenantWebSessionOfTheRequestedTenantCanIssue() throws Exception {
    var request = request(UUID.randomUUID(), PersonalTokenRequest.ScopesValue.SCOPES_read);
    for (var kind :
        List.of(
            CredentialKind.CREDENTIAL_KIND_USER_PAT,
            CredentialKind.CREDENTIAL_KIND_USER_OAUTH,
            CredentialKind.CREDENTIAL_KIND_ADMIN_PAT,
            CredentialKind.CREDENTIAL_KIND_SERVICE))
      rejected(
          Status.Code.PERMISSION_DENIED,
          "SESSION_REQUIRED",
          create(tenantSession().toBuilder().setCredentialKind(kind).build(), request));
    rejected(
        Status.Code.PERMISSION_DENIED,
        "SESSION_REQUIRED",
        create(
            tenantSession().toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                .clearTenantId()
                .build(),
            request));
    rejected(
        Status.Code.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        create(tenantSession().toBuilder().setTenantId(UUID.randomUUID().toString()).build(), request));
    assertEquals(0, reads.get());
    verifyNoInteractions(store);
  }

  @Test
  void capabilityPolicyGatesIssuanceBeforeAnyWrite() throws Exception {
    var request = request(UUID.randomUUID(), PersonalTokenRequest.ScopesValue.SCOPES_read);
    flags.put(Flag.PERSONAL_API_TOKENS, false);
    rejected(Status.Code.PERMISSION_DENIED, "CAPABILITY_DISABLED", create(tenantSession(), request));
    flags.put(Flag.PERSONAL_API_TOKENS, true);
    // A paid membership elsewhere is irrelevant: the FREE flag is checked for this tenant.
    plan = PlanKind.FREE;
    rejected(Status.Code.PERMISSION_DENIED, "CAPABILITY_DISABLED", create(tenantSession(), request));
    plan = PlanKind.UNKNOWN;
    rejected(Status.Code.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", create(tenantSession(), request));
    plan = PlanKind.PAID;
    membership = Membership.StateValue.STATE_REMOVED;
    rejected(Status.Code.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE", create(tenantSession(), request));
    membership = Membership.StateValue.STATE_ACTIVE;
    rejected(
        Status.Code.PERMISSION_DENIED,
        "MEMBERSHIP_INACTIVE",
        create(tenantSession().toBuilder().setMembershipGeneration("1").build(), request));
    rejected(
        Status.Code.PERMISSION_DENIED,
        "SCOPE_DENIED",
        create(tenantSession().toBuilder().clearScopes().addScopes("read").build(), request));
    factsAvailable = false;
    rejected(Status.Code.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", create(tenantSession(), request));
    verifyNoInteractions(store);
  }

  @Test
  void issuanceUsesVerifiedIdentityAndReturnsTenantMetadata() throws Exception {
    UUID key = UUID.randomUUID(), token = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-06T12:00:00Z");
    plan = PlanKind.FREE;
    flags.put(Flag.FREE_PERSONAL_API_TOKENS, true);
    when(store.issue(
            user, session, tenant, "CI bot", List.of("read", "write"), 30, key, requestId))
        .thenReturn(
            Mono.just(
                new TenantPersonalTokenStore.Issued(
                    token,
                    tenant,
                    "CI bot",
                    List.of("read", "write"),
                    now,
                    now.plusSeconds(2592000),
                    "test-secret")));
    var forged =
        request(
                key,
                PersonalTokenRequest.ScopesValue.SCOPES_read,
                PersonalTokenRequest.ScopesValue.SCOPES_write)
            .toBuilder()
            .setContext(
                RequestContext.newBuilder()
                    .setContext(
                        tenantSession().toBuilder()
                            .setActorUserId(UUID.randomUUID().toString())
                            .setSessionId(UUID.randomUUID().toString())))
            .build();
    var created =
        inbound(tenantSession(), () -> service.createTenantPersonalToken(Mono.just(forged)))
            .block();
    assertEquals(token.toString(), created.getMetadata().getId());
    assertEquals(tenant.toString(), created.getMetadata().getTenantId());
    assertEquals(
        List.of(PersonalToken.ScopesValue.SCOPES_read, PersonalToken.ScopesValue.SCOPES_write),
        created.getMetadata().getScopesList());
    assertEquals("2026-11-06T12:00:00Z", created.getMetadata().getCreatedAt());
    assertEquals("test-secret", created.getSecret());
    verify(store).issue(user, session, tenant, "CI bot", List.of("read", "write"), 30, key, requestId);
    verifyNoMoreInteractions(store);
  }

  @Test
  void ordinaryPatCannotChangeAccountOrIssueAnotherPat() throws Exception {
    var pat =
        tenantSession().toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
            .clearSessionId()
            .setCredentialId(credential)
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
                  pat,
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
  void unknownScopeIsRejectedBeforePolicy() throws Exception {
    rejected(
        Status.Code.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        create(
            tenantSession(),
            request(UUID.randomUUID(), PersonalTokenRequest.ScopesValue.SCOPES_UNSPECIFIED)));
    assertEquals(0, reads.get());
    verifyNoInteractions(store);
  }

  @Test
  void authenticationExposesOnlyAnOrdinaryUserPatBinding() {
    UUID token = UUID.randomUUID(), grant = UUID.randomUUID();
    Instant expires = Instant.parse("2026-12-06T12:00:00Z");
    when(store.authenticate("tsk_pat_secret"))
        .thenReturn(
            Mono.just(
                new TenantPersonalTokenStore.Binding(
                    token, grant, 3, user, tenant, List.of("read"), expires, 7)));
    var binding =
        service
            .authenticateTenantPersonalToken(
                Mono.just(
                    AuthenticateTenantPersonalTokenRequest.newBuilder()
                        .setSecret("tsk_pat_secret")
                        .build()))
            .block();
    assertEquals(CredentialKind.CREDENTIAL_KIND_USER_PAT, binding.getCredentialKind());
    assertEquals(tenant.toString(), binding.getTenantId());
    assertEquals(token.toString(), binding.getCredentialId());
    assertEquals(grant.toString(), binding.getGrantId());
    assertEquals("3", binding.getGrantGeneration());
    assertEquals("7", binding.getActorGeneration());
    assertEquals(List.of("read"), binding.getScopesList());
    assertEquals("2026-12-06T12:00:00Z", binding.getExpiresAt());
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingTrustProviderFailsClosedForIssuanceOnly() {
    var guard =
        new TenantPersonalTokenInterceptor(
            new DefaultListableBeanFactory().getBeanProvider(RpcContextSecurity.class), "test");
    ServerCall call = mock(ServerCall.class);
    ServerCallHandler next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor())
        .thenReturn(TenantPersonalTokenServiceGrpc.getCreateTenantPersonalTokenMethod());
    guard.interceptCall(call, new Metadata(), next);
    verify(call)
        .close(
            argThat(
                status ->
                    status.getCode() == Status.Code.UNAVAILABLE
                        && "Tenant token trust unavailable".equals(status.getDescription())),
            any(Metadata.class));
    verifyNoInteractions(next);

    ServerCall edge = mock(ServerCall.class);
    when(edge.getMethodDescriptor())
        .thenReturn(TenantPersonalTokenServiceGrpc.getAuthenticateTenantPersonalTokenMethod());
    var headers = new Metadata();
    guard.interceptCall(edge, headers, next);
    verify(next).startCall(edge, headers);
  }
}
