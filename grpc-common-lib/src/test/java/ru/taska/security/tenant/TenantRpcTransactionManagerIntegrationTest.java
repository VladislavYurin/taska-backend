package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TenantRpcTransactionManagerIntegrationTest extends RpcContextFixture {
  static TenantIsolationFixture database;
  static final Duration WAIT = Duration.ofSeconds(15);

  @BeforeAll
  static void database() throws Exception {
    database = new TenantIsolationFixture(2);
  }

  @AfterAll
  static void close() {
    if (database != null) database.close();
  }

  Mono<String> current() {
    return database
        .raw
        .sql("SELECT coalesce(current_setting('app.tenant_id',true),'') AS tenant")
        .map((r, m) -> r.get("tenant", String.class))
        .one();
  }

  @Test
  void separateSagaTransactionsRemainScopedAndDoNotLeakAfterErrorOrCancel() {
    var verified = sender.authorize(context(), RPC, POLICY).block(WAIT);
    var manager = new TenantRpcTransactionManager(database.pool);
    var normal = TransactionalOperator.create(manager);
    var definition =
        new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var independent = TransactionalOperator.create(manager, definition);
    var work =
        normal.transactional(
            current()
                .flatMap(
                    outer -> {
                      assertEquals(TENANT, outer);
                      return independent
                          .transactional(current())
                          .flatMap(
                              inner -> {
                                assertEquals(TENANT, inner);
                                return current();
                              });
                    }));
    assertEquals(TENANT, RpcContextBridge.install(work, verified).block(WAIT));
    assertEquals("", current().block(WAIT));
    assertEquals("", current().block(WAIT));
    StepVerifier.create(
            RpcContextBridge.install(
                normal.transactional(
                    current().then(Mono.error(new IllegalStateException("owned rollback")))),
                verified))
        .expectError(IllegalStateException.class)
        .verify(WAIT);
    assertEquals("", current().block(WAIT));
    StepVerifier.create(
            RpcContextBridge.install(normal.transactional(current().then(Mono.never())), verified))
        .thenAwait(Duration.ofMillis(100))
        .thenCancel()
        .verify(WAIT);
    assertEquals("", current().block(WAIT));
    assertEquals("", current().block(WAIT));
  }
}
