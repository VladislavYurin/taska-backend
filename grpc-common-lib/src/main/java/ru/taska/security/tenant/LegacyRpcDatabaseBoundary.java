package ru.taska.security.tenant;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Bind legacy repository queries to verified scope on the same transaction/connection as writes.
 */
public final class LegacyRpcDatabaseBoundary {
  static final io.grpc.Context.Key<LegacyRpcDatabaseBoundary> KEY =
      io.grpc.Context.key("taska-legacy-database-scope");
  private final DatabaseClient db;
  private final TransactionalOperator transactions;

  public LegacyRpcDatabaseBoundary(ConnectionFactory factory) {
    db = DatabaseClient.create(factory);
    transactions = TransactionalOperator.create(new TenantRpcTransactionManager(factory));
  }

  public static <T> Mono<T> protect(Mono<T> work) {
    var boundary = KEY.get();
    return boundary == null ? work : boundary.scoped(work);
  }

  public void validateConfiguration(boolean enabled, String rootTable) {
    if (!java.util.Set.of("projects", "issues", "workflows").contains(rootTable))
      throw new IllegalArgumentException("Unsupported database owner");
    var state =
        db.sql(
                "SELECT current_setting('app.rpc_scope_enforced',true)='true' AS scoped, NOT"
                    + " (rolsuper OR rolinherit OR rolbypassrls OR rolcreatedb OR rolcreaterole OR"
                    + " rolreplication) AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE"
                    + " member=r.oid OR roleid=r.oid) AND NOT EXISTS(SELECT 1 FROM pg_class c WHERE"
                    + " c.relowner=r.oid AND c.relnamespace='taska'::regnamespace) AS safe FROM"
                    + " pg_roles r WHERE rolname=current_user")
            .map(
                (row, m) ->
                    new boolean[] {
                      Boolean.TRUE.equals(row.get("scoped", Boolean.class)),
                      Boolean.TRUE.equals(row.get("safe", Boolean.class))
                    })
            .one()
            .block(java.time.Duration.ofSeconds(10));
    if (state == null || state[0] != enabled || (enabled && !state[1]))
      throw new IllegalStateException(
          "RPC storage cutover requires matching scope setting and a safe runtime role");
    if (!enabled
        && Boolean.TRUE.equals(
            db.sql(
                    "SELECT EXISTS(SELECT 1 FROM taska."
                        + rootTable
                        + " WHERE tenant_id IS NOT NULL) AS scoped")
                .map((row, m) -> row.get("scoped", Boolean.class))
                .one()
                .block(java.time.Duration.ofSeconds(10))))
      throw new IllegalStateException("Tenant-scoped data cannot be opened by the legacy RPC mode");
  }

  private <T> Mono<T> scoped(Mono<T> work) {
    return Mono.deferContextual(
        context -> {
          var verified = VerifiedTenantContext.require(context);
          if (verified.tenantId() == null)
            return Mono.error(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
          return transactions.transactional(
              db.sql(
                      "SELECT"
                          + " set_config('app.rpc_scope_enforced','true',true),set_config('app.tenant_id',:tenant,true),set_config('app.actor_id',:actor,true)")
                  .bind("tenant", verified.tenantId())
                  .bind("actor", verified.actorId() == null ? "" : verified.actorId())
                  .fetch()
                  .rowsUpdated()
                  .then(work));
        });
  }
}
