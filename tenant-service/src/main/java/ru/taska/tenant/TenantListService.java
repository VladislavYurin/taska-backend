package ru.taska.tenant;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListTenantsRpcRequest;
import ru.taska.api.b2b.v1.PageRequest;
import ru.taska.api.b2b.v1.TenantPage;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Fresh actor-scoped tenant metadata read through the dedicated RLS-protected database function. */
final class TenantListService {
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final DatabaseClient database;
  private final TransactionalOperator transactions;
  private final MembershipCursor cursors;

  TenantListService(
      DatabaseClient database, TransactionalOperator transactions, MembershipCursor cursors) {
    this.database = database;
    this.transactions = transactions;
    this.cursors = cursors;
  }

  Mono<TenantPage> list(ListTenantsRpcRequest request) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            error -> TenantRegistryService.fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .flatMap(
            verified -> {
              TrustedTenantContext claims = RpcContextBridge.details(verified).getContext();
              if (!"listTenants".equals(verified.action())
                  || verified.actorId() == null
                  || verified.tenantId() != null
                  || claims.hasTenantId()
                  || !verified.actorId().equals(claims.getActorUserId())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !claims.hasSessionId()
                  || claims.getSessionId().isBlank()
                  || !positiveGeneration(claims.getActorGeneration())
                  || !verified.scopes().contains("tenants:select"))
                return Mono.error(
                    TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              if (!request.getUnknownFields().asMap().isEmpty())
                return Mono.error(
                    TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));

              PageRequest page =
                  request.hasPage() ? request.getPage() : PageRequest.getDefaultInstance();
              int limit = page.getLimit() == 0 ? DEFAULT_LIMIT : page.getLimit();
              if (limit < 1 || limit > MAX_LIMIT || !page.getUnknownFields().asMap().isEmpty())
                return Mono.error(
                    TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));

              cursors.requireConfigured();
              UUID actor = TenantRegistryService.uuid(verified.actorId());
              long actorGeneration = Long.parseLong(claims.getActorGeneration());
              String binding = binding(verified, claims, limit);
              MembershipCursor.Position position =
                  page.hasCursor() ? cursors.decode(page.getCursor(), binding) : null;
              if (position != null && position.generation() != actorGeneration)
                return Mono.error(
                    TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));

              return transactions.transactional(
                  database
                      .sql("SELECT set_config('app.actor_id',:actor,true)")
                      .bind("actor", actor.toString())
                      .fetch()
                      .one()
                      .then(fetch(actor, position, binding, actorGeneration, limit)));
            });
  }

  private Mono<TenantPage> fetch(
      UUID actor,
      MembershipCursor.Position position,
      String binding,
      long actorGeneration,
      int limit) {
    var query =
        database
            .sql(
                "SELECT id,name,owner_id,lifecycle,created_at,version,owner_version,"
                    + "plan_version_id,creation_operation_id,creation_kind,"
                    + "creation_closure_reason,creation_closed_at,creation_deadline_at,"
                    + "pending_first_payment FROM taska.list_tenants_for_actor("
                    + ":actor,:after_created_at,:after_tenant_id,:fetch_limit)")
            .bind("actor", actor)
            .bind("fetch_limit", limit + 1);
    if (position == null) {
      query =
          query
              .bindNull("after_created_at", OffsetDateTime.class)
              .bindNull("after_tenant_id", UUID.class);
    } else {
      // MembershipCursor's position slots carry the tenant row's created_at and id here.
      query =
          query
              .bind(
                  "after_created_at",
                  position.joinedAt().atOffset(ZoneOffset.UTC))
              .bind("after_tenant_id", position.userId());
    }
    return query
        .fetch()
        .all()
        .collectList()
        .map(rows -> page(rows, binding, actorGeneration, limit));
  }

  private TenantPage page(
      List<Map<String, Object>> rows, String binding, long actorGeneration, int limit) {
    var result = TenantPage.newBuilder();
    int visible = Math.min(limit, rows.size());
    for (Map<String, Object> row : rows.subList(0, visible))
      result.addItems(TenantRegistryService.tenant(row));
    if (rows.size() > limit) {
      var last = rows.get(limit - 1);
      result.setNextCursor(
          cursors.encode(
              binding,
              actorGeneration,
              ((OffsetDateTime) last.get("created_at")).toInstant(),
              (UUID) last.get("id")));
    }
    return result.build();
  }

  private static String binding(
      VerifiedTenantContext verified, TrustedTenantContext claims, int limit) {
    var scopes = String.join("\u0000", new TreeSet<>(verified.scopes()));
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            TenantRegistryService.digest(
                "tenant-list/v1",
                verified.actorId(),
                claims.getActorGeneration(),
                claims.getCredentialKind().name(),
                claims.getSessionId(),
                Integer.toString(limit),
                scopes));
  }

  private static boolean positiveGeneration(String generation) {
    try {
      long value = Long.parseLong(generation);
      return value > 0 && Long.toString(value).equals(generation);
    } catch (NumberFormatException error) {
      return false;
    }
  }
}
