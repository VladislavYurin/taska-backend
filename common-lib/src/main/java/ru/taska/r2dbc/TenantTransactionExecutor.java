package ru.taska.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.r2dbc.connection.ConnectionFactoryUtils;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.connection.TransactionAwareConnectionFactoryProxy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * A local tenant transaction; callbacks must compose SQL work, never start detached subscriptions.
 */
public final class TenantTransactionExecutor {
  private static final Object FRAME_KEY = new Object();
  private final ConnectionFactory factory;
  private final DatabaseClient setup;
  private final DatabaseClient scoped;
  private final TransactionalOperator transactions;
  private final ConcurrentMap<ReactiveTransaction, Frame> frames = new ConcurrentHashMap<>();

  private static final class Frame {
    final TenantTransactionExecutor owner;
    final String tenant, actor;
    final ReactiveTransaction status;
    final AtomicBoolean active = new AtomicBoolean(true);
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile Connection physical;

    Frame(
        TenantTransactionExecutor owner, String tenant, String actor, ReactiveTransaction status) {
      this.owner = owner;
      this.tenant = tenant;
      this.actor = actor;
      this.status = status;
    }
  }

  public TenantTransactionExecutor(ConnectionFactory connectionFactory) {
    this.factory = independentFactory(connectionFactory);
    var manager = new R2dbcTransactionManager(factory);
    this.setup = DatabaseClient.create(factory);
    this.transactions =
        TransactionalOperator.create(
            new ReactiveTransactionManager() {
              public Mono<ReactiveTransaction> getReactiveTransaction(
                  TransactionDefinition definition) {
                return manager.getReactiveTransaction(definition);
              }

              public Mono<Void> commit(ReactiveTransaction status) {
                return manager.commit(status).doFinally(signal -> frames.remove(status));
              }

              public Mono<Void> rollback(ReactiveTransaction status) {
                return Mono.defer(
                        () -> {
                          Frame frame = frames.get(status);
                          if (frame == null || !frame.cancelled.get() || frame.physical == null)
                            return manager.rollback(status);
                          // Query cancellation is not guaranteed by R2DBC subscription
                          // cancellation. Discard
                          // the physical connection before releasing its pooled handle, so queued
                          // SQL cannot
                          // outlive this scope or interfere with the next borrower. Disconnect
                          // rolls back PG.
                          Mono<Void> abort =
                              frame.physical
                                      instanceof
                                      io.r2dbc.postgresql.api.PostgresqlConnection postgres
                                  ? postgres
                                      .cancelRequest()
                                      .timeout(java.time.Duration.ofSeconds(5))
                                      .onErrorComplete()
                                  : Mono.empty();
                          return abort
                              .then(Mono.from(frame.physical.close()))
                              .onErrorResume(
                                  closeError ->
                                      manager.rollback(status).then(Mono.error(closeError)))
                              // Spring must still unbind/release the pooled handle. ROLLBACK on the
                              // already
                              // closed physical connection can fail, after its transaction was
                              // discarded.
                              .then(manager.rollback(status).onErrorComplete());
                        })
                    .doFinally(signal -> frames.remove(status));
              }
            });
    var aware = new TransactionAwareConnectionFactoryProxy(factory);
    this.scoped =
        DatabaseClient.create(
            new ConnectionFactory() {
              @Override
              public Mono<? extends Connection> create() {
                return Mono.deferContextual(
                    context -> {
                      Frame frame = context.getOrDefault(FRAME_KEY, null);
                      if (frame == null
                          || frame.owner != TenantTransactionExecutor.this
                          || !frame.active.get())
                        return Mono.error(
                            failure(
                                DomainStatus.FAILED_PRECONDITION,
                                "Tenant transaction is not active"));
                      checkIdentity(context, frame);
                      return TransactionSynchronizationManager.forCurrentTransaction()
                          .flatMap(
                              manager -> {
                                if (!manager.isActualTransactionActive()
                                    || !manager.hasResource(factory))
                                  return Mono.error(
                                      failure(
                                          DomainStatus.FAILED_PRECONDITION,
                                          "Tenant connection is not bound"));
                                // The proxy suppresses logical close while this exact factory's
                                // connection is bound.
                                return Mono.from(aware.create());
                              });
                    });
              }

              @Override
              public ConnectionFactoryMetadata getMetadata() {
                return factory.getMetadata();
              }
            });
  }

  /**
   * Emits a result only after commit. For several rows, collect them inside the callback. A nested
   * call joins this transaction; an error marks it rollback-only even if caught by its caller.
   */
  public <T> Mono<T> execute(Function<DatabaseClient, Mono<T>> work) {
    Objects.requireNonNull(work);
    return Mono.deferContextual(
        context -> {
          Frame frame = context.getOrDefault(FRAME_KEY, null);
          final VerifiedTenantContext identity;
          try {
            identity = identity(context);
          } catch (RuntimeException error) {
            if (frame != null && frame.active.get()) frame.status.setRollbackOnly();
            return Mono.error(error);
          }
          String tenant = identity.tenantId(),
              actor = identity.actorId() == null ? "" : identity.actorId();
          if (frame != null) {
            try {
              if (frame.owner.factory != factory || !frame.active.get())
                throw failure(
                    DomainStatus.FAILED_PRECONDITION,
                    "Cannot join another tenant connection factory");
              checkIdentity(context, frame);
            } catch (RuntimeException error) {
              if (frame.active.get()) frame.status.setRollbackOnly();
              return Mono.error(error);
            }
            return Mono.defer(() -> Objects.requireNonNull(work.apply(frame.owner.scoped)))
                .doOnError(error -> frame.status.setRollbackOnly())
                .doOnCancel(
                    () -> {
                      frame.status.setRollbackOnly();
                      frame.cancelled.set(true);
                      frame.active.set(false);
                    });
          }
          // Never install tenant settings in an ambient transaction that this executor did not
          // create.
          return TransactionSynchronizationManager.forCurrentTransaction()
              .map(manager -> manager.isActualTransactionActive() || manager.hasResource(factory))
              .onErrorReturn(NoTransactionException.class, false)
              .flatMap(
                  ambient -> {
                    if (ambient)
                      return Mono.error(
                          failure(
                              DomainStatus.FAILED_PRECONDITION,
                              "Ambient transaction is not tenant-owned"));
                    return transactions
                        .execute(
                            status -> {
                              Frame owned = new Frame(this, tenant, actor, status);
                              frames.put(status, owned);
                              return ConnectionFactoryUtils.getConnection(factory)
                                  .flatMap(
                                      connection -> {
                                        owned.physical = physical(connection);
                                        return setup
                                            .sql(
                                                "SELECT set_config('app.tenant_id', :tenant, true),"
                                                    + " set_config('app.actor_id', :actor, true)")
                                            .bind("tenant", tenant)
                                            .bind("actor", actor)
                                            .fetch()
                                            .all()
                                            .then()
                                            .then(
                                                Mono.defer(
                                                    () ->
                                                        Objects.requireNonNull(work.apply(scoped))))
                                            .doOnSuccess(
                                                result -> {
                                                  if (status.isRollbackOnly())
                                                    throw failure(
                                                        DomainStatus.ABORTED,
                                                        "Tenant transaction marked rollback-only");
                                                });
                                      })
                                  .doOnCancel(() -> owned.cancelled.set(true))
                                  .contextWrite(c -> c.put(FRAME_KEY, owned))
                                  .doFinally(signal -> owned.active.set(false));
                            })
                        .singleOrEmpty();
                  });
        });
  }

  private static ConnectionFactory independentFactory(ConnectionFactory supplied) {
    ConnectionFactory target = Objects.requireNonNull(supplied);
    for (int depth = 0; target instanceof TransactionAwareConnectionFactoryProxy proxy; depth++) {
      if (depth >= 16) throw new IllegalArgumentException("Connection factory proxy cycle");
      target = Objects.requireNonNull(proxy.getTargetConnectionFactory());
    }
    return target;
  }

  private static Connection physical(Connection supplied) {
    Connection target = supplied;
    for (int depth = 0; target instanceof io.r2dbc.spi.Wrapped<?> wrapped; depth++) {
      if (depth >= 16) throw new IllegalArgumentException("Connection wrapper cycle");
      Object unwrapped = wrapped.unwrap();
      if (!(unwrapped instanceof Connection next) || next == target) break;
      target = next;
    }
    return target;
  }

  private static VerifiedTenantContext identity(ContextView context) {
    final VerifiedTenantContext identity;
    try {
      identity = VerifiedTenantContext.require(context);
    } catch (IllegalStateException error) {
      throw failure(DomainStatus.UNAUTHENTICATED, "Verified tenant context required");
    }
    if (!canonicalUuid(identity.tenantId())
        || (identity.actorId() != null && !canonicalUuid(identity.actorId())))
      throw failure(DomainStatus.PERMISSION_DENIED, "Tenant-scoped authority required");
    return identity;
  }

  private static void checkIdentity(ContextView context, Frame frame) {
    final VerifiedTenantContext identity;
    try {
      identity = identity(context);
    } catch (RuntimeException error) {
      if (frame.active.get()) frame.status.setRollbackOnly();
      throw error;
    }
    if (!frame.tenant.equals(identity.tenantId())
        || !frame.actor.equals(identity.actorId() == null ? "" : identity.actorId())) {
      frame.status.setRollbackOnly();
      throw failure(DomainStatus.PERMISSION_DENIED, "Cannot change transaction authority");
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static DomainException failure(DomainStatus status, String message) {
    return new DomainException(status, message);
  }
}
