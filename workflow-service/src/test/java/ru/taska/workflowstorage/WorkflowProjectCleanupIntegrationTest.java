package ru.taska.workflowstorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.taska.security.tenant.WorkflowTestContext.as;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ProjectDeletionProof;
import ru.taska.quota.ProjectDeletionAuthority;

/** PostgreSQL/RLS cleanup; primary proof управляется fixture, настоящий transport покрыт E2E. */
class WorkflowProjectCleanupIntegrationTest extends TenantWorkflowFixture {
  @Test
  void deletesOwnedGraphButRetainsTemplatesReceiptsAndOtherTenant() {
    UUID project = UUID.randomUUID(), operation = UUID.randomUUID();
    var own = graph(A, project);
    var foreign = graph(B, project);
    var primary = mock(ProjectDeletionAuthority.class);
    when(primary.accepted(A, project, operation))
        .thenReturn(Mono.just(ProjectDeletionProof.getDefaultInstance()));
    var cleanup = new WorkflowProjectCleanup(store, primary);
    assertTrue(as(A, ACTOR, cleanup.cleanup(project, operation)).block(WAIT).getComplete());
    assertTrue(as(A, ACTOR, cleanup.cleanup(project, operation)).block(WAIT).getComplete());
    assertEquals(1L, count("workflows"));
    assertEquals(2L, count("tenant_workflow_templates"));
    assertEquals(2L, count("workflow_template_instances"));
    assertNotNull(as(B, ACTOR, store.execute(s -> s.find(foreign.id()))).block(WAIT));
    var error =
        assertThrows(
            Exception.class,
            () ->
                as(
                        A,
                        ACTOR,
                        store.execute(
                            s -> s.insertWorkflow(UUID.randomUUID(), project, "Поздний граф")))
                    .block(WAIT));
    assertEquals("23514", state(error));
    assertEquals(ru.taska.exception.DomainStatus.NOT_FOUND,org.junit.jupiter.api.Assertions.assertInstanceOf(ru.taska.exception.DomainException.class,error).getStatus());
    assertEquals(1L, count("workflows"));
  }
}
