package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.WorkflowCreateService;
import ru.taska.service.WorkflowMutationService;
import ru.taska.service.WorkflowMutationService.Operation;
import ru.taska.transport.grpc.issue.WorkflowIssueMigrationClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowMutationIntegrationTest extends AbstractIT {
    @Autowired WorkflowCreateService creator;
    @Autowired WorkflowMutationService service;
    @MockitoBean ProjectRoleChecker roles;
    @MockitoBean WorkflowIssueMigrationClient issues;
    private UUID project, actor, workflow, review;

    @MockitoBean
    private ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;

    @BeforeEach
    void createGraph() {
        org.mockito.Mockito.when(projectClient.listIssueTypes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(java.util.List.of(ru.taska.domain.IssueType.values())));

        project = UUID.randomUUID(); actor = UUID.randomUUID();
        when(roles.checkProjectRole(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(issues.migrate(any(), any(), any(), any())).thenReturn(Mono.just(0L));
        var created = creator.validateAndCreateWorkflow("r", "n", actor, WorkflowCreationDto.builder()
                .projectId(project).name("Test").issueTypes(List.of(IssueType.TASK))
                .statuses(List.of(status("TODO", 10), status("REVIEW", 20), status("DONE", 30)))
                .transitions(List.of(edge("TODO", "REVIEW", 10), edge("REVIEW", "DONE", 20), edge("TODO", "DONE", 30)))
                .build()).block();
        workflow = created.workflow().getId();
        review = created.statuses().stream().filter(s -> s.getStatusKey().equals("REVIEW")).findFirst().orElseThrow().getId();
    }

    @Test
    void concurrentEditsWithSameVersionProduceOneSuccessAndOneConflict() {
        var results = Flux.merge(service.mutate(Operation.UPDATE_WORKFLOW, command("One", null, null)).materialize(),
                service.mutate(Operation.UPDATE_WORKFLOW, command("Two", null, null)).materialize()).collectList().block();
        assertThat(results.stream().filter(signal -> signal.isOnNext())).hasSize(1);
        assertThat(results.stream().filter(signal -> signal.isOnError()).map(signal -> signal.getThrowable()).toList())
                .singleElement().satisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.ABORTED));
        assertThat(databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("version", Integer.class)).one().block()).isEqualTo(2);
    }

    @Test
    void deletionRemovesIncidentEdgesAndBumpsVersion() {
        var changed = service.mutate(Operation.DELETE_STATUS, command(null, review, "DONE")).block();
        assertThat(changed.workflow().workflow().getVersion()).isEqualTo(2);
        assertThat(changed.workflow().statuses()).extracting(s -> s.getStatusKey()).containsExactly("TODO", "DONE");
        assertThat(changed.workflow().transitions()).hasSize(1);
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.transitions WHERE from_status_id=:id OR to_status_id=:id")
                .bind("id", review).map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
    }

    @Test
    void deletingOwnWorkflowFallsBackToDefaultAndDropsOnlyItsGraph() {
        var fallback = service.mutate(Operation.DELETE_WORKFLOW, command(null, null, null)).block();
        assertThat(fallback.shared()).isTrue();
        assertThat(fallback.workflow().workflow().getId()).isNotEqualTo(workflow);
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
    }

    private WorkflowMutation command(String name, UUID status, String target) {
        return new WorkflowMutation("r", "n", actor, project, workflow, 1, status, null,
                name, null, null, null, target, Map.of("REVIEW", "DONE"));
    }

    private CreateWorkflowStatusDto status(String key, int order) {
        return CreateWorkflowStatusDto.builder().statusKey(key).name(key).category(StatusCategory.TODO).sortOrder(order).build();
    }

    private CreateWorkflowTransitionDto edge(String from, String to, int order) {
        return CreateWorkflowTransitionDto.builder().fromStatusKey(from).toStatusKey(to).name(from + " to " + to).sortOrder(order).build();
    }
}
