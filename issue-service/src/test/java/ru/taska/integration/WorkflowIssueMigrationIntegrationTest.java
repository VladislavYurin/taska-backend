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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.service.WorkflowIssueMigrationService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.kafka.OutboxEventScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class WorkflowIssueMigrationIntegrationTest extends AbstractIT {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ru.taska.service.WorkflowBindingPendingGuard workflowBindingGuard;
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean GrpcProjectServiceClient projects;
    @Autowired IssueRepository repository;
    @Autowired WorkflowIssueMigrationService service;
    @Autowired DatabaseClient db;
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), workflow = UUID.randomUUID();

    @BeforeEach
    void admin() {
        org.mockito.Mockito.when(workflowBindingGuard.requireWritable(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.empty());
        when(projects.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(
                CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true)
                        .setRole(ProjectRole.PROJECT_ROLE_ADMIN).setProjectArchived(false).build()));
    }

    @Test
    void movesOnlyScopedActiveIssuesAndWritesHistoryOutboxExactlyOnce() {
        var one = issue(project, IssueType.TASK, 1);
        var two = issue(project, IssueType.TASK, 2);
        var bug = issue(project, IssueType.BUG, 3);
        var other = issue(UUID.randomUUID(), IssueType.TASK, 4);
        var deleted = issue(project, IssueType.TASK, 5);
        db.sql("UPDATE taska.issues SET deleted_at=now() WHERE id=:id").bind("id", deleted.getId()).fetch().rowsUpdated().block();
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE"), "r", "n").block()).isEqualTo(2);
        for (var changed : List.of(one, two)) {
            assertThat(repository.findById(changed.getId()).block().getStatusKey()).isEqualTo("DONE");
            assertThat(count("issue_history", "issue_id", changed.getId())).isEqualTo(1);
            assertThat(count("outbox_events", "aggregate_id", changed.getId())).isEqualTo(1);
        }
        for (var untouched : List.of(bug, other, deleted)) {
            assertThat(repository.findById(untouched.getId()).block().getStatusKey()).isEqualTo("REVIEW");
        }
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE"), "retry", "n").block()).isZero();
        assertThat(count("issue_history", "issue_id", one.getId())).isEqualTo(1);
    }

    @Test
    void missingMappingReturnsCountWithoutChangingAnyIssue() {
        var one = issue(project, IssueType.TASK, 1);
        issue(project, IssueType.TASK, 2);
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"), Map.of(), "r", "n"))
                .expectErrorSatisfies(error -> {
                    assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) error).getDetails()).containsEntry("reason", "WORKFLOW_ISSUES_UNMAPPED")
                            .containsEntry("issueCount", "2").containsEntry("statusKeys", "REVIEW");
                }).verify();
        assertThat(repository.findById(one.getId()).block().getStatusKey()).isEqualTo("REVIEW");
        assertThat(count("issue_history", "issue_id", one.getId())).isZero();
    }

    @Test
    void outboxFailureRollsBackStatusAndHistory() {
        var one = issue(project, IssueType.TASK, 1);
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT workflow_migration_test_check CHECK (aggregate_id <> '" + one.getId() + "'::uuid)")
                .fetch().rowsUpdated().block();
        try {
            StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                            Map.of("REVIEW", "DONE"), "r", "n"))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(repository.findById(one.getId()).block().getStatusKey()).isEqualTo("REVIEW");
            assertThat(count("issue_history", "issue_id", one.getId())).isZero();
            assertThat(count("outbox_events", "aggregate_id", one.getId())).isZero();
        } finally {
            db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT workflow_migration_test_check").fetch().rowsUpdated().block();
        }
    }

    @Test
    void memberCannotRunAdministrativeMigration() {
        when(projects.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(
                CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true)
                        .setRole(ProjectRole.PROJECT_ROLE_MEMBER).build()));
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO"), Map.of(), "r", "n"))
                .expectErrorSatisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED)).verify();
    }

    @Test
    void archivedProjectDoesNotMoveIssuesOrWriteHistory() {
        var one = issue(project, IssueType.TASK, 1);
        when(projects.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(
                CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true)
                        .setRole(ProjectRole.PROJECT_ROLE_ADMIN).setProjectArchived(true).build()));
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", "DONE"), "r", "n"))
                .expectErrorSatisfies(e -> {
                    var error = (DomainException) e;
                    assertThat(error.getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                    assertThat(error.getDetails()).containsEntry("reason", "PROJECT_ARCHIVED");
                }).verify();
        assertThat(repository.findById(one.getId()).block().getStatusKey()).isEqualTo("REVIEW");
        assertThat(count("issue_history", "issue_id", one.getId())).isZero();
        assertThat(count("outbox_events", "aggregate_id", one.getId())).isZero();
    }

    @Test
    void missingArchiveCapabilityRejectsMigration() {
        when(projects.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(
                CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true)
                        .setRole(ProjectRole.PROJECT_ROLE_ADMIN).build()));
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", "DONE"), "r", "n"))
                .expectErrorSatisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.UNAVAILABLE))
                .verify();
    }

    @Test
    void emptyMappingTargetIsInvalidAndDoesNotChangeIssue() {
        var one = issue(project, IssueType.TASK, 1);
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", ""), "r", "n"))
                .expectErrorSatisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT))
                .verify();
        assertThat(repository.findById(one.getId()).block().getStatusKey()).isEqualTo("REVIEW");
    }


    @Test
    void deletingOneStatusLeavesOtherUnplacedIssuesUntouchedAndUncounted() {
        var selected = issue(project, IssueType.TASK, 1);
        var legacy = issue(project, IssueType.TASK, 2);
        db.sql("UPDATE taska.issues SET status_key='LEGACY' WHERE id=:id").bind("id", legacy.getId()).then().block();
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of(), List.of("REVIEW"), "r", "n"))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getDetails())
                        .containsExactlyInAnyOrderEntriesOf(Map.of("reason", "WORKFLOW_ISSUES_UNMAPPED", "issueCount", "1", "statusKeys", "REVIEW")))
                .verify();
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE"), List.of("REVIEW"), "r", "n").block()).isEqualTo(1);
        assertThat(repository.findById(selected.getId()).block().getStatusKey()).isEqualTo("DONE");
        assertThat(repository.findById(legacy.getId()).block().getStatusKey()).isEqualTo("LEGACY");
        assertThat(count("issue_history", "issue_id", legacy.getId())).isZero();
        assertThat(count("outbox_events", "aggregate_id", legacy.getId())).isZero();
    }

    @Test
    void concurrentScopedMigrationsDoNotDuplicateEvents() {
        var selected = issue(project, IssueType.TASK, 1);
        var outcomes = reactor.core.publisher.Flux.merge(
                service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", "DONE"), List.of("REVIEW"), "first", "n"),
                service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", "DONE"), List.of("REVIEW"), "second", "n"))
                .collectList().block();
        assertThat(outcomes).containsExactlyInAnyOrder(1L, 0L);
        assertThat(repository.findById(selected.getId()).block().getStatusKey()).isEqualTo("DONE");
        assertThat(count("issue_history", "issue_id", selected.getId())).isEqualTo(1);
        assertThat(count("outbox_events", "aggregate_id", selected.getId())).isEqualTo(1);
    }

    @Test
    void synchronousLimitIsPermanentPreconditionAndRollsBackAllWrites() {
        db.sql("""
                INSERT INTO taska.issues (project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)
                SELECT :project, n, 'LIMIT-' || gen_random_uuid(), 'TASK', 'Limit', 'REVIEW', 'MEDIUM', :actor
                FROM generate_series(1,5001) n
                """).bind("project", project).bind("actor", actor).then().block();
        StepVerifier.create(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                        Map.of("REVIEW", "DONE"), List.of("REVIEW"), "r", "n"))
                .expectErrorSatisfies(error -> {
                    assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) error).getDetails()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("reason", "WORKFLOW_MIGRATION_LIMIT", "limit", "5000"));
                }).verify();
        assertThat(db.sql("SELECT count(*) FROM taska.issues WHERE project_id=:id AND status_key='REVIEW' AND version=1")
                .bind("id", project).map(row -> row.get(0, Long.class)).one().block()).isEqualTo(5001);
        assertThat(db.sql("SELECT count(*) FROM taska.issue_history h JOIN taska.issues i ON h.issue_id=i.id WHERE i.project_id=:id")
                .bind("id", project).map(row -> row.get(0, Long.class)).one().block()).isZero();
    }

    @Test
    void workflowDeletionMovesOnlyOwnAndExplicitlyMappedStatusesLeavingOtherOrphansUnplaced() {
        var own = issue(project, IssueType.TASK, 1);
        var explicit = issue(project, IssueType.TASK, 2);
        var untouched = issue(project, IssueType.TASK, 3);
        db.sql("UPDATE taska.issues SET status_key='EXPLICIT_OLD' WHERE id=:id").bind("id", explicit.getId()).then().block();
        db.sql("UPDATE taska.issues SET status_key='OLD_ORPHAN' WHERE id=:id").bind("id", untouched.getId()).then().block();
        var sources = List.of("TODO", "REVIEW", "DONE", "EXPLICIT_OLD");
        var mapping = Map.of("REVIEW", "DONE", "EXPLICIT_OLD", "TODO");
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"), mapping, sources, "r", "n").block()).isEqualTo(2);
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"), mapping, sources, "retry", "n").block()).isZero();
        assertThat(repository.findById(own.getId()).block().getStatusKey()).isEqualTo("DONE");
        assertThat(repository.findById(explicit.getId()).block().getStatusKey()).isEqualTo("TODO");
        assertThat(repository.findById(untouched.getId()).block().getStatusKey()).isEqualTo("OLD_ORPHAN");
        for (var moved : List.of(own, explicit)) {
            assertThat(count("issue_history", "issue_id", moved.getId())).isEqualTo(1);
            assertThat(count("outbox_events", "aggregate_id", moved.getId())).isEqualTo(1);
        }
        assertThat(count("issue_history", "issue_id", untouched.getId())).isZero();
        assertThat(count("outbox_events", "aggregate_id", untouched.getId())).isZero();
    }

    @Test
    void unchangedDefaultStatusesAndUnrelatedOrphansDoNotSpendTheWorkflowMigrationLimit() {
        var one = issue(project, IssueType.TASK, 1);
        db.sql("""
                INSERT INTO taska.issues (project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)
                SELECT :project, n, 'UNCHANGED-' || gen_random_uuid(), 'TASK', 'Unchanged',
                    CASE WHEN n%2=0 THEN 'TODO' ELSE 'OLD_ORPHAN' END, 'MEDIUM', :actor
                FROM generate_series(2,10003) n
                """).bind("project", project).bind("actor", actor).then().block();
        assertThat(service.migrate(project, actor, workflow, List.of("TASK"), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE"), List.of("TODO", "REVIEW", "DONE"), "r", "n").block()).isEqualTo(1);
        assertThat(repository.findById(one.getId()).block().getStatusKey()).isEqualTo("DONE");
        assertThat(db.sql("SELECT count(*) FROM taska.issues WHERE project_id=:id AND status_key IN ('TODO','OLD_ORPHAN') AND version=1")
                .bind("id", project).map(row -> row.get(0, Long.class)).one().block()).isEqualTo(10002);
    }

    private Issue issue(UUID project, IssueType type, int number) {
        return repository.save(Issue.builder().projectId(project).issueNumber(number).issueKey(UUID.randomUUID().toString())
                .summary("Migration").description("").statusKey("REVIEW").issueType(type).priority(IssuePriority.MEDIUM)
                .reporterId(actor).build()).block();
    }

    private long count(String table, String column, UUID id) {
        return db.sql("SELECT count(*) AS n FROM taska." + table + " WHERE " + column + "=:id")
                .bind("id", id).map((r, m) -> r.get("n", Long.class)).one().block();
    }
}
