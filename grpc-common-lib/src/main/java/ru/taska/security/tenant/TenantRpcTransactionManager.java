package ru.taska.security.tenant;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Also scopes independently committed saga transactions after the outer transaction is suspended.
 */
public final class TenantRpcTransactionManager extends R2dbcTransactionManager {
  public TenantRpcTransactionManager(ConnectionFactory factory) {
    super(factory);
  }

  @Override
  protected Mono<Void> prepareTransactionalConnection(
      Connection connection, TransactionDefinition definition) {
    return super.prepareTransactionalConnection(connection, definition)
        .then(
            Mono.deferContextual(
                context -> {
                  if (!context.hasKey(VerifiedTenantContext.class)) return Mono.empty();
                  var verified = VerifiedTenantContext.require(context);
                  if (verified.tenantId() == null)
                    return Mono.error(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                  var statement =
                      connection
                          .createStatement(
                              "SELECT"
                                  + " set_config('app.rpc_scope_enforced','true',true),set_config('app.tenant_id',$1,true),set_config('app.actor_id',$2,true)")
                          .bind(0, verified.tenantId())
                          .bind(1, verified.actorId() == null ? "" : verified.actorId());
                  return Flux.from(statement.execute())
                      .flatMap(result -> result.getRowsUpdated())
                      .then();
                }));
  }
}
