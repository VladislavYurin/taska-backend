package ru.taska.tenant;

import java.security.*;
import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/**
 * Primary-DB authority. Each response's rows and generations come from one SQL statement snapshot.
 */
public final class TenantMembershipService {
  private final TenantTransactionExecutor transactions;
  private final MembershipCursor cursors;
  private final Set<String> callers;

  private record Authority(
      UUID tenant, VerifiedTenantContext verified, TrustedTenantContext claims) {}

  public TenantMembershipService(
      TenantTransactionExecutor transactions, MembershipCursor cursors, Set<String> callers) {
    this.transactions = transactions;
    this.cursors = cursors;
    this.callers = Set.copyOf(callers);
    if (callers.isEmpty() || callers.stream().anyMatch(v -> !v.matches("[a-z][a-z0-9-]{0,63}")))
      throw new IllegalArgumentException("Explicit membership reader service allowlist required");
  }

  private Mono<Authority> authority(String target, String action, boolean backend) {
    return Mono.defer(
        () -> {
          UUID tenant = TenantRegistryService.uuid(target);
          return VerifiedTenantContext.current()
              .onErrorMap(
                  IllegalStateException.class,
                  e ->
                      TenantRegistryService.fail(
                          DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
              .map(
                  verified -> {
                    var c = RpcContextBridge.details(verified).getContext();
                    if (!target.equals(verified.tenantId()) || !action.equals(verified.action()))
                      throw TenantRegistryService.fail(
                          DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
                    if (backend) {
                      if (c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                          || !callers.contains(c.getIssuerService())
                          || !verified.scopes().contains("memberships:read"))
                        throw TenantRegistryService.fail(
                            DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
                    } else {
                      boolean supported =
                          switch (c.getCredentialKind()) {
                            case CREDENTIAL_KIND_WEB_TENANT ->
                                c.hasSessionId() && !c.getSessionId().isBlank();
                            case CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_USER_OAUTH ->
                                c.hasCredentialId() && !c.getCredentialId().isBlank();
                            default -> false;
                          };
                      if (!supported
                          || verified.actorId() == null
                          || !verified.scopes().contains("read")
                          || c.getActorGeneration().isBlank()
                          || !c.hasMembershipGeneration()
                          || c.getMembershipGeneration().isBlank())
                        throw TenantRegistryService.fail(
                            DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
                    }
                    return new Authority(tenant, verified, c);
                  });
        });
  }

  private static String binding(Authority a, int limit) {
    var c = a.claims();
    var map = new TreeMap<String, Object>();
    map.put("tenant", a.tenant().toString());
    map.put("actor", a.verified().actorId());
    map.put("actorGeneration", c.getActorGeneration());
    map.put("membershipGeneration", c.getMembershipGeneration());
    map.put("kind", c.getCredentialKind().name());
    map.put("session", c.getSessionId());
    map.put("credential", c.getCredentialId());
    map.put("grant", c.getGrantId());
    map.put("grantGeneration", c.getGrantGeneration());
    map.put("scopes", new TreeSet<>(a.verified().scopes()));
    map.put("limit", limit);
    map.put("order", "joined_at,user_id;ACTIVE,SUSPENDED");
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      tools.jackson.databind.json.JsonMapper.builder()
                          .build()
                          .writeValueAsBytes(map)));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException(error);
    }
  }

  public Mono<MembershipPage> list(ListTenantMembersRpcRequest request) {
    return authority(request.getTenantId(), "listTenantMembers", false)
        .flatMap(
            a -> {
              int limit = request.getPage().getLimit() == 0 ? 50 : request.getPage().getLimit();
              if (limit < 1 || limit > 100)
                return Mono.error(
                    TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));
              cursors.requireConfigured();
              String binding = binding(a, limit);
              var position =
                  request.getPage().hasCursor()
                      ? cursors.decode(request.getPage().getCursor(), binding)
                      : null;
              // LEFT LATERAL preserves an authorization/generation header even on an empty page.
              return transactions.execute(
                  db ->
                      db.sql(
                              "SELECT t.owner_id,t.membership_version AS generation,t.lifecycle,p.*"
                                  + " FROM taska.tenants t JOIN taska.tenant_memberships actor ON"
                                  + " actor.tenant_id=t.id AND actor.user_id=:actor AND"
                                  + " actor.status='ACTIVE' AND actor.version::text=:actor_version"
                                  + " LEFT JOIN LATERAL (SELECT"
                                  + " m.tenant_id,m.user_id,m.role,m.status,m.version,m.joined_at"
                                  + " FROM taska.tenant_memberships m WHERE m.tenant_id=t.id AND"
                                  + " m.status IN ('ACTIVE','SUSPENDED') AND (:has_cursor=false OR"
                                  + " t.membership_version=:generation) AND (:has_cursor=false OR"
                                  + " (m.joined_at,m.user_id)>(:after_at,:after_user)) ORDER BY"
                                  + " m.joined_at,m.user_id LIMIT :fetch) p ON true WHERE"
                                  + " t.id=:tenant AND t.lifecycle<>'DELETED' ORDER BY"
                                  + " p.joined_at,p.user_id")
                          .bind("actor", TenantRegistryService.uuid(a.verified().actorId()))
                          .bind("actor_version", a.claims().getMembershipGeneration())
                          .bind("tenant", a.tenant())
                          .bind("has_cursor", position != null)
                          .bind("generation", position == null ? 0L : position.generation())
                          .bind(
                              "after_at",
                              position == null
                                  ? Instant.EPOCH.atOffset(ZoneOffset.UTC)
                                  : position.joinedAt().atOffset(ZoneOffset.UTC))
                          .bind("after_user", position == null ? new UUID(0, 0) : position.userId())
                          .bind("fetch", limit + 1)
                          .fetch()
                          .all()
                          .collectList()
                          .map(
                              rows -> {
                                if (rows.isEmpty())
                                  throw TenantRegistryService.fail(
                                      DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND");
                                TenantCreationLifecycle.requireWorking(
                                    Tenant.newBuilder()
                                        .setLifecycle(
                                            Tenant.LifecycleValue.valueOf(
                                                "LIFECYCLE_" + rows.getFirst().get("lifecycle")))
                                        .build());
                                long generation =
                                    ((Number) rows.getFirst().get("generation")).longValue();
                                if (position != null && position.generation() != generation)
                                  throw TenantRegistryService.fail(
                                      DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
                                var records =
                                    rows.stream()
                                        .filter(row -> row.get("user_id") != null)
                                        .toList();
                                var result = MembershipPage.newBuilder();
                                for (var row : records.subList(0, Math.min(limit, records.size())))
                                  result.addItems(publicMember(row));
                                if (records.size() > limit) {
                                  var last = records.get(limit - 1);
                                  result.setNextCursor(
                                      cursors.encode(
                                          binding,
                                          generation,
                                          ((OffsetDateTime) last.get("joined_at")).toInstant(),
                                          (UUID) last.get("user_id")));
                                }
                                return result.build();
                              }));
            });
  }

  private static Membership publicMember(Map<String, Object> row) {
    UUID tenant = (UUID) row.get("tenant_id"), user = (UUID) row.get("user_id");
    // Composite membership identity; stable across role changes/rejoin, never an auth credential.
    byte[] bytes =
        TenantRegistryService.digest("taska:membership:v1", tenant.toString(), user.toString());
    var bits = java.nio.ByteBuffer.wrap(bytes);
    UUID id =
        new UUID(
            (bits.getLong() & 0xffffffffffff0fffL) | 0x8000L,
            (bits.getLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    return Membership.newBuilder()
        .setId(id.toString())
        .setTenantId(tenant.toString())
        .setUserId(user.toString())
        .setRole(role(row))
        .setState(state(row))
        .setIsOwner(user.equals(row.get("owner_id")))
        .setVersion(row.get("version").toString())
        .build();
  }

  private static Membership.RoleValue role(Map<String, Object> row) {
    return Membership.RoleValue.valueOf("ROLE_" + row.get("role"));
  }

  private static Membership.StateValue state(Map<String, Object> row) {
    return Membership.StateValue.valueOf("STATE_" + row.get("status"));
  }

  private Mono<MembershipValidationBatch> read(Authority a, List<UUID> users) {
    return transactions.execute(
        db ->
            db.sql(
                    "SELECT t.id AS tenant_id,t.owner_id,t.membership_version AS"
                        + " generation,t.lifecycle,requested.user_id AS"
                        + " requested_user_id,m.user_id,m.role,m.status,m.version,m.joined_at FROM"
                        + " taska.tenants t CROSS JOIN unnest(:users::uuid[]) WITH ORDINALITY AS"
                        + " requested(user_id,n) LEFT JOIN taska.tenant_memberships m ON"
                        + " m.tenant_id=t.id AND m.user_id=requested.user_id WHERE t.id=:tenant AND"
                        + " t.lifecycle<>'DELETED' ORDER BY requested.n")
                .bind("users", users.toArray(UUID[]::new))
                .bind("tenant", a.tenant())
                .fetch()
                .all()
                .collectList()
                .map(
                    rows -> {
                      if (rows.isEmpty())
                        throw TenantRegistryService.fail(
                            DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND");
                      String generation = rows.getFirst().get("generation").toString();
                      var result =
                          MembershipValidationBatch.newBuilder()
                              .setTenantId(a.tenant().toString())
                              .setTenantMembershipVersion(generation)
                              .setTenantLifecycle(
                                  Tenant.LifecycleValue.valueOf(
                                      "LIFECYCLE_" + rows.getFirst().get("lifecycle")));
                      for (var row : rows) {
                        var item =
                            MembershipValidation.newBuilder()
                                .setUserId(row.get("requested_user_id").toString())
                                .setFound(row.get("user_id") != null);
                        if (row.get("user_id") != null)
                          item.setMembership(
                              MembershipSnapshot.newBuilder()
                                  .setTenantId(a.tenant().toString())
                                  .setUserId(row.get("user_id").toString())
                                  .setRole(role(row))
                                  .setState(state(row))
                                  .setVersion(row.get("version").toString())
                                  .setTenantMembershipVersion(generation)
                                  .setIsOwner(row.get("user_id").equals(row.get("owner_id")))
                                  .setJoinedAt(
                                      ((OffsetDateTime) row.get("joined_at"))
                                          .toInstant()
                                          .toString()));
                        result.addItems(item);
                      }
                      return result.build();
                    }));
  }

  public Mono<MembershipSnapshot> get(GetMembershipRpcRequest request) {
    return authority(request.getTenantId(), "getMembership", true)
        .flatMap(a -> read(a, List.of(TenantRegistryService.uuid(request.getUserId()))))
        .flatMap(
            result ->
                result.getItems(0).getFound()
                    ? Mono.just(result.getItems(0).getMembership())
                    : Mono.error(
                        TenantRegistryService.fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  public Mono<MembershipValidationBatch> batch(BatchValidateMembershipsRpcRequest request) {
    return authority(request.getTenantId(), "batchValidateMemberships", true)
        .flatMap(
            a -> {
              if (request.getUserIdsCount() < 1 || request.getUserIdsCount() > 100)
                throw TenantRegistryService.fail(
                    DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
              var users =
                  request.getUserIdsList().stream().map(TenantRegistryService::uuid).toList();
              if (new HashSet<>(users).size() != users.size())
                throw TenantRegistryService.fail(
                    DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
              return read(a, users);
            });
  }
}
