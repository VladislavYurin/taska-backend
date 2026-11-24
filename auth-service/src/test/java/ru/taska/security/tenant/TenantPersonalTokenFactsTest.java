package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.security.pat.*;

/** Production source and handler; primary reads are bounded fixtures, not a deployed E2E. */
class TenantPersonalTokenFactsTest extends TenantPersonalTokenBoundaryTest {
  @Test
  void currentFactsAreReadAgainAndAnOutageNeverReusesAnAllow() throws Exception {
    var reads = mock(ManagedDomainReads.class);
    var identity = mock(RpcTrustBackend.class);
    var source = new TenantPersonalTokenFacts(reads, identity, Clock.systemUTC());
    var handler = new TenantPersonalTokenGrpcService(store,
        new TenantPersonalTokenAdmission(new CapabilityEvaluator(source, Clock.systemUTC())));
    var claims = tenantSession().toBuilder().setCredentialId(session.toString()).build();
    var actor = RpcActorFacts.newBuilder().setActorUserId(user.toString()).setActorGeneration("1")
        .setCredentialId(session.toString()).setSessionId(session.toString())
        .addAllScopes(List.of("read", "write")).setExpiresAt(Instant.now().plusSeconds(25).toString()).build();
    when(identity.actorFacts(any(), any(), any())).thenReturn(Mono.just(actor));
    var membership = MembershipSnapshot.newBuilder().setTenantId(tenant.toString()).setUserId(user.toString())
        .setState(Membership.StateValue.STATE_ACTIVE).setRole(Membership.RoleValue.ROLE_MEMBER)
        .setVersion("2").setTenantMembershipVersion("2").build();
    when(reads.actorMembership(any())).thenReturn(Mono.just(MembershipValidationBatch.newBuilder()
        .setTenantId(tenant.toString()).setTenantLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
        .addItems(MembershipValidation.newBuilder().setUserId(user.toString()).setFound(true).setMembership(membership)).build()));
    var planId = UUID.randomUUID().toString();
    var entitlement = Entitlement.newBuilder().setTenantId(tenant.toString()).setPlanVersionId(planId)
        .setVersion("1").setValidFrom(Instant.now().minusSeconds(60).toString()).setIssuedAt(Instant.now().toString());
    for (var kind : List.of(QuotaLimit.KindValue.KIND_PROJECT, QuotaLimit.KindValue.KIND_SEAT,
        QuotaLimit.KindValue.KIND_STORAGE_BYTES, QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      entitlement.addLimits(QuotaLimit.newBuilder().setKind(kind).setLimit("50"));
    when(reads.entitlement(tenant, 0)).thenReturn(Mono.just(entitlement.build()));
    when(reads.plan(planId)).thenReturn(Mono.just(CapabilityFacts.PlanKind.FREE));
    var values = new ArrayList<PlatformFlag>();
    for (String key : PlatformConfigurationSnapshotCache.keys()) {
      var flag = PlatformFlag.newBuilder().setKey(key).setOwner("tenant-service").setVersion("1")
          .setEffectiveAt(Instant.now().minusSeconds(2).toString());
      if (key.endsWith("_SECONDS")) flag.setType(PlatformFlag.TypeValue.TYPE_DURATION_SECONDS)
          .setIntegerValue("86400").setDefaultIntegerValue("86400").setMinimumValue("1").setMaximumValue("31536000");
      else flag.setType(PlatformFlag.TypeValue.TYPE_BOOLEAN).setBooleanValue(true).setDefaultBooleanValue(true);
      values.add(flag.build());
    }
    when(reads.configuration()).thenReturn(Mono.just(PlatformConfigurationSnapshotCache.snapshot(values)));
    var target = new CapabilityFacts.Target(tenant, null, user, null);
    var evaluator = new CapabilityEvaluator(source, Clock.systemUTC());
    var first = inbound(claims, () -> RpcContextBridge.inbound(evaluator.evaluate(Action.PAT_CREATE, target))).block();
    assertTrue(first.capability().getAllowed());
    var expired = claims.toBuilder().setAction("approveTenantOAuthGrant")
        .setExpiresAt(Instant.now().minusSeconds(1).toString()).build();
    var expiredFailure = assertThrows(ru.taska.exception.DomainException.class, () -> inbound(expired,
        () -> RpcContextBridge.inbound(source.admitOAuth(tenant, user))).block());
    assertEquals("DEPENDENCY_UNAVAILABLE", expiredFailure.getDetails().get("code"));
    var blocked = values.stream().map(f -> f.getKey().equals("FREE_MCP")
        ? f.toBuilder().setVersion("2").setBooleanValue(false).build() : f).toList();
    when(reads.configuration()).thenReturn(Mono.just(PlatformConfigurationSnapshotCache.snapshot(blocked)));
    var oauthClaims = claims.toBuilder().setAction("approveTenantOAuthGrant").build();
    var refusal = assertThrows(ru.taska.exception.DomainException.class, () -> inbound(oauthClaims,
        () -> RpcContextBridge.inbound(source.admitOAuth(tenant, user))).block());
    assertEquals("CAPABILITY_DISABLED", refusal.getDetails().get("code"));
    assertEquals("FREE_MCP", refusal.getDetails().get("disabledFlags"));
    when(reads.configuration()).thenReturn(Mono.error(new IllegalStateException("owned outage")));
    rejected(io.grpc.Status.Code.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", inbound(claims,
        () -> handler.createTenantPersonalToken(Mono.just(request(UUID.randomUUID(), PersonalTokenRequest.ScopesValue.SCOPES_read)))));
    verify(identity, times(4)).actorFacts(any(), any(), any());
    verify(reads, times(4)).actorMembership(any());
    verifyNoInteractions(store);
  }
}
