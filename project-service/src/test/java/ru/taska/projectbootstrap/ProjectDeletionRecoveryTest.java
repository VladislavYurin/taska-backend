package ru.taska.projectbootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.projectstorage.ProjectDeletions;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.ProjectTestContext;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Ошибка authority или списка одного tenant не должна останавливать следующий. */
class ProjectDeletionRecoveryTest {
  @Test
  void authorizationFailureDoesNotStarveTheNextTenant() {
    isolation(true);
  }

  @Test
  void pendingFailureDoesNotStarveTheNextTenant() {
    isolation(false);
  }

  private void isolation(boolean authorityFails) {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), operation = UUID.randomUUID();
    var deletions = mock(ProjectDeletions.class);
    var contexts = mock(ManagedServiceContexts.class);
    var firstContext = ProjectTestContext.cleanup(first, VerifiedTenantContext.current()).block();
    var secondContext = ProjectTestContext.cleanup(second, VerifiedTenantContext.current()).block();
    when(contexts.authorize(
            anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenAnswer(
            i ->
                first.equals(i.getArgument(4))
                    ? authorityFails
                        ? Mono.error(new IllegalStateException("Отказ authority"))
                        : Mono.just(firstContext)
                    : Mono.just(secondContext));
    when(deletions.pending())
        .thenAnswer(
            i ->
                VerifiedTenantContext.current()
                    .flatMap(
                        c ->
                            c.tenantId().equals(first.toString())
                                ? Mono.error(new IllegalStateException("Отказ списка"))
                                : Mono.just(List.of(operation))));
    when(deletions.recover(operation)).thenReturn(Mono.just(Operation.getDefaultInstance()));
    var worker = new ProjectDeletionRecovery(deletions, contexts, first + "," + second);
    worker.tick();
    worker.tick();
    verify(deletions, times(2)).recover(operation);
  }
}
