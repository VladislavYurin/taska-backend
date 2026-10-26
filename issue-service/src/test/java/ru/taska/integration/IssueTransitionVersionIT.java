package ru.taska.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.domain.dto.IssueWithHistory;
import ru.taska.domain.entity.Issue;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.service.transition.IssueTransitionService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.workflow.IssueTransitionValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

/** Real PostgreSQL transactions, row locks and history/outbox writes. */
class IssueTransitionVersionIT extends AbstractIT {
    @Autowired private IssueTransitionService service;
    @Autowired private IssueRepository repository;
    @Autowired private DatabaseClient database;
    @MockitoBean private ProjectRoleChecker roles;
    @MockitoBean private IssueTransitionValidator validator;

    private UUID projectId;
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TRANSITION = UUID.randomUUID();
    private UUID issueId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        issueId = UUID.randomUUID();
        database.sql("""
                INSERT INTO taska.issues (id, project_id, issue_number, issue_key, issue_type,
                    summary, status_key, priority, reporter_id, version)
                VALUES (:id, :project, 1, :key, 'TASK', 'Before', 'TODO', 'MEDIUM', :actor, 1)
                """).bind("id", issueId).bind("project", projectId).bind("key", "TST-" + issueId)
                .bind("actor", ACTOR).then().block();
        Mockito.lenient().when(roles.checkProjectWriteRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.lenient().when(validator.validateTransition(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(Mono.just("IN_PROGRESS"));
    }

    @Test
    void matchingVersionCommitsStatusHistoryAndOutbox() {
        IssueWithHistory result = transition(1).block();
        assertThat(result).isNotNull();
        assertThat(result.getIssue().getStatusKey()).isEqualTo("IN_PROGRESS");
        assertThat(result.getIssue().getVersion()).isEqualTo(2);
        assertEvents(1);
    }

    @Test
    void staleVersionDoesNotWriteOrValidateWorkflow() {
        changeSummary();
        StepVerifier.create(transition(1)).expectErrorSatisfies(error -> assertConflict(error, 2, "TODO")).verify();
        assertUnchangedAfterSummaryEdit();
        Mockito.verifyNoInteractions(validator);
    }

    @Test
    void changeBetweenValidationAndTransactionIsDetected() {
        Mockito.when(validator.validateTransition(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(Mono.defer(() -> summaryChange().thenReturn("IN_PROGRESS")));
        StepVerifier.create(transition(1)).expectErrorSatisfies(error -> assertConflict(error, 2, "TODO")).verify();
        assertUnchangedAfterSummaryEdit();
        Mockito.verify(validator).validateTransition(anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void twoWritersWithSameVersionOnlyOneCommits() {
        // Both requests read version 1 and validate it before either enters the transaction.
        Sinks.One<Void> bothValidated = Sinks.one();
        AtomicInteger validated = new AtomicInteger();
        Mockito.when(validator.validateTransition(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(Mono.defer(() -> {
                    if (validated.incrementAndGet() == 2) bothValidated.tryEmitEmpty();
                    return bothValidated.asMono().thenReturn("IN_PROGRESS");
                }));
        List<Signal<IssueWithHistory>> results = Mono.zip(
                transition(1).materialize().subscribeOn(Schedulers.parallel()),
                transition(1).materialize().subscribeOn(Schedulers.parallel()))
                .map(pair -> List.of(pair.getT1(), pair.getT2())).block(Duration.ofSeconds(15));
        assertThat(results).isNotNull();
        assertThat(results).filteredOn(Signal::isOnNext).hasSize(1);
        assertThat(results).filteredOn(Signal::isOnError).singleElement()
                .satisfies(result -> assertConflict(result.getThrowable(), 2, "IN_PROGRESS"));
        Issue stored = repository.findById(issueId).block();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getStatusKey()).isEqualTo("IN_PROGRESS");
        assertEvents(1);
    }

    @Test
    void oldClientWithoutVersionStillTransitions() {
        changeSummary();
        IssueWithHistory result = transition(null).block();
        assertThat(result.getIssue().getVersion()).isEqualTo(3);
        assertThat(result.getIssue().getSummary()).isEqualTo("Edited");
        assertEvents(1);
    }

    @Test
    void forbiddenCallerDoesNotReceiveVersionOrStatus() {
        changeSummary();
        Mockito.when(roles.checkProjectWriteRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Forbidden")));
        StepVerifier.create(transition(1)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(DomainException.class);
            DomainException failure = (DomainException) error;
            assertThat(failure.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
            assertThat(failure.getDetails()).isEmpty();
        }).verify();
        assertUnchangedAfterSummaryEdit();
        Mockito.verifyNoInteractions(validator);
    }

    @Test
    void sameStatusStillChecksVersionAndDoesNotWrite() {
        Mockito.when(validator.validateTransition(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(Mono.just("TODO"));
        assertThat(transition(1).block().getIssue().getVersion()).isEqualTo(1);
        assertEvents(0);
        changeSummary();
        StepVerifier.create(transition(1)).expectErrorSatisfies(error -> assertConflict(error, 2, "TODO")).verify();
        assertUnchangedAfterSummaryEdit();
    }

    private Mono<IssueWithHistory> transition(Integer version) {
        return service.transitionIssue("tas-422", "issue-service", issueId, TRANSITION, ACTOR, "", version);
    }

    private Mono<Void> summaryChange() {
        return database.sql("UPDATE taska.issues SET summary='Edited', version=version+1 WHERE id=:id")
                .bind("id", issueId).then();
    }

    private void changeSummary() { summaryChange().block(); }

    private void assertUnchangedAfterSummaryEdit() {
        Issue stored = repository.findById(issueId).block();
        assertThat(stored.getSummary()).isEqualTo("Edited");
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getStatusKey()).isEqualTo("TODO");
        assertEvents(0);
    }

    private void assertEvents(long expected) {
        assertThat(database.sql("SELECT count(*) FROM taska.issue_history WHERE issue_id=:id")
                .bind("id", issueId).map(row -> row.get(0, Long.class)).one().block()).isEqualTo(expected);
        assertThat(database.sql("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id")
                .bind("id", issueId).map(row -> row.get(0, Long.class)).one().block()).isEqualTo(expected);
    }

    private static void assertConflict(Throwable error, int version, String status) {
        assertThat(error).isInstanceOf(DomainException.class);
        DomainException conflict = (DomainException) error;
        assertThat(conflict.getStatus()).isEqualTo(DomainStatus.ABORTED);
        assertThat(conflict.getDetails()).isEqualTo(Map.of("currentVersion", Integer.toString(version), "currentStatus", status));
    }
}
