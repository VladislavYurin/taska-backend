package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.r2dbc.spi.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;

/**
 * Deterministic driver failure injection; real lifecycle/RLS is covered by the PostgreSQL suite.
 */
class TenantTransactionExecutorFailureTest {
  Connection connection;
  TenantTransactionExecutor executor;
  VerifiedTenantContext context;
  RuntimeException closeFailure = new IllegalStateException("synthetic close failure");
  RuntimeException rollbackFailure = new IllegalStateException("synthetic rollback failure");

  @BeforeEach
  void setup() {
    connection = mock(Connection.class);
    var statement = mock(Statement.class);
    var result = mock(Result.class);
    var autoCommit = new AtomicBoolean(true);
    when(connection.isAutoCommit()).thenAnswer(i -> autoCommit.get());
    when(connection.beginTransaction(any(TransactionDefinition.class)))
        .thenAnswer(i -> Mono.fromRunnable(() -> autoCommit.set(false)));
    when(connection.setAutoCommit(anyBoolean()))
        .thenAnswer(i -> Mono.fromRunnable(() -> autoCommit.set(i.getArgument(0))));
    when(connection.getTransactionIsolationLevel()).thenReturn(IsolationLevel.READ_COMMITTED);
    when(connection.createStatement(anyString())).thenReturn(statement);
    when(statement.bind(anyInt(), any())).thenReturn(statement);
    doReturn(Flux.just(result)).when(statement).execute();
    doReturn(Flux.empty())
        .when(result)
        .map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, Object>>any());
    when(connection.close()).thenReturn(Mono.error(closeFailure));
    when(connection.rollbackTransaction()).thenReturn(Mono.empty());
    executor =
        new TenantTransactionExecutor(
            new ConnectionFactory() {
              public Mono<? extends Connection> create() {
                return Mono.just(connection);
              }

              public ConnectionFactoryMetadata getMetadata() {
                return () -> "PostgreSQL";
              }
            });
    context =
        new VerifiedTenantContext(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "test.sql",
            Set.of("write"),
            new byte[0]);
  }

  static boolean contains(Throwable root, Throwable wanted, Set<Throwable> seen) {
    if (root == null || !seen.add(root)) return false;
    if (root == wanted) return true;
    if (contains(root.getCause(), wanted, seen)) return true;
    for (Throwable suppressed : root.getSuppressed())
      if (contains(suppressed, wanted, seen)) return true;
    return false;
  }

  static boolean contains(Throwable root, Throwable wanted) {
    return contains(root, wanted, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  Mono<String> cancelledNested() {
    return executor
        .execute(
            db ->
                executor
                    .execute(inner -> Mono.<String>never())
                    .timeout(Duration.ofMillis(20))
                    .onErrorReturn("recovered"))
        .contextWrite(context::install);
  }

  static boolean hasApplicationAbort(Throwable error) {
    if (error instanceof DomainException d && d.getStatus() == DomainStatus.ABORTED) return true;
    for (var s : error.getSuppressed()) if (hasApplicationAbort(s)) return true;
    return error.getCause() != null && hasApplicationAbort(error.getCause());
  }

  @Test
  void closeAndRollbackFailureKeepBothCausesAndApplicationFailure() {
    when(connection.rollbackTransaction()).thenReturn(Mono.error(rollbackFailure));
    StepVerifier.create(cancelledNested())
        .expectErrorSatisfies(
            error -> {
              assertTrue(contains(error, closeFailure));
              assertTrue(contains(error, rollbackFailure));
              assertTrue(hasApplicationAbort(error));
            })
        .verify();
    verify(connection, times(1)).rollbackTransaction();
  }

  @Test
  void successfulRollbackAfterCloseFailureKeepsApplicationFailure() {
    StepVerifier.create(cancelledNested())
        .expectErrorSatisfies(
            error -> {
              assertTrue(contains(error, closeFailure));
              assertTrue(hasApplicationAbort(error));
            })
        .verify();
    verify(connection, times(1)).rollbackTransaction();
  }

  @Test
  void ordinaryCallbackErrorSurvivesRollbackFailure() {
    var applicationFailure = new IllegalArgumentException("synthetic application failure");
    when(connection.rollbackTransaction()).thenReturn(Mono.error(rollbackFailure));
    StepVerifier.create(
            executor.execute(db -> Mono.error(applicationFailure)).contextWrite(context::install))
        .expectErrorSatisfies(
            error -> {
              assertTrue(contains(error, applicationFailure));
              assertTrue(contains(error, rollbackFailure));
            })
        .verify();
  }
}
