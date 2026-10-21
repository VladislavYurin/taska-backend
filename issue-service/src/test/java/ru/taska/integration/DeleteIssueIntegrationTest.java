package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;
import ru.taska.transport.kafka.OutboxEventScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class DeleteIssueIntegrationTest extends AbstractIT {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String REQUEST_ID = "delete-regression";
    private static final String NODE_ID = "issue-service";

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @MockitoBean
    private OutboxEventScheduler outboxEventScheduler;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        issueRepository.deleteAll().block();
    }

    @ParameterizedTest
    @MethodSource("forbiddenCallers")
    void unauthorizedDelete_leavesIssueAndEventsUnchanged(ProjectRole role, boolean member) {
        Issue issue = createIssue();
        allowRole(role, member);

        StepVerifier.create(delete(issue.getId()))
                .expectErrorSatisfies(error -> assertStatus(error, DomainStatus.PERMISSION_DENIED))
                .verify();

        assertUnchanged(issue);
    }

    static Stream<Arguments> forbiddenCallers() {
        return Stream.of(
                Arguments.of(ProjectRole.PROJECT_ROLE_VIEWER, true),
                Arguments.of(ProjectRole.PROJECT_ROLE_MEMBER, false));
    }

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"PROJECT_ROLE_ADMIN", "PROJECT_ROLE_MEMBER"})
    void authorizedDelete_isRecordedOnceAndRepeatedDeleteReturnsNotFound(ProjectRole role) {
        Issue issue = createIssue();
        allowRole(role, true);

        Issue deleted = delete(issue.getId()).block();
        assertThat(deleted).isNotNull();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getVersion()).isEqualTo(issue.getVersion() + 1);
        assertThat(issueRepository.findActiveById(issue.getId()).block()).isNull();
        assertThat(countHistory(issue.getId())).isEqualTo(1);
        assertThat(countOutbox(issue.getId())).isEqualTo(1);

        StepVerifier.create(delete(issue.getId()))
                .expectErrorSatisfies(error -> assertStatus(error, DomainStatus.NOT_FOUND))
                .verify();
        assertThat(countHistory(issue.getId())).isEqualTo(1);
        assertThat(countOutbox(issue.getId())).isEqualTo(1);
    }

    @Test
    void unavailableAuthorization_leavesIssueAndEventsUnchanged() {
        Issue issue = createIssue();
        when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.error(io.grpc.Status.UNAVAILABLE.asRuntimeException()));

        StepVerifier.create(delete(issue.getId())).expectError().verify();

        assertUnchanged(issue);
    }

    @Test
    void missingIssue_returnsNotFound() {
        StepVerifier.create(delete(UUID.randomUUID()))
                .expectErrorSatisfies(error -> assertStatus(error, DomainStatus.NOT_FOUND))
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue_history", "outbox_events"})
    void eventWriteFailure_rollsBackDeletionAndBothEvents(String table) {
        Issue issue = createIssue();
        allowRole(ProjectRole.PROJECT_ROLE_ADMIN, true);
        String eventType = table.equals("issue_history") ? "DELETED" : EventType.ISSUE_DELETED.getValue();
        databaseClient.sql("""
                CREATE OR REPLACE FUNCTION taska.it_fail_delete_event() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'delete integration test failure';
                END;
                $$
                """).then().block();
        databaseClient.sql("CREATE TRIGGER it_fail_delete_event BEFORE INSERT ON taska." + table
                + " FOR EACH ROW WHEN (NEW.event_type = '" + eventType + "')"
                + " EXECUTE FUNCTION taska.it_fail_delete_event()")
                .then().block();
        try {
            StepVerifier.create(delete(issue.getId()))
                    .expectErrorMatches(error -> error.getMessage().contains("delete integration test failure"))
                    .verify();
        } finally {
            databaseClient.sql("DROP TRIGGER it_fail_delete_event ON taska." + table).then().block();
        }

        assertUnchanged(issue);
    }

    @Test
    void concurrentDeletes_onlyOneSucceedsAndWritesEvents() {
        Issue issue = createIssue();
        allowRole(ProjectRole.PROJECT_ROLE_MEMBER, true);

        var results = Mono.zip(
                delete(issue.getId()).materialize().subscribeOn(Schedulers.parallel()),
                delete(issue.getId()).materialize().subscribeOn(Schedulers.parallel())).block();

        assertThat(results).isNotNull();
        var signals = java.util.List.of(results.getT1(), results.getT2());
        assertThat(signals).filteredOn(signal -> signal.isOnNext()).hasSize(1);
        assertThat(signals).filteredOn(signal -> signal.isOnError()).singleElement()
                .satisfies(signal -> assertStatus(signal.getThrowable(), DomainStatus.NOT_FOUND));
        assertThat(countHistory(issue.getId())).isEqualTo(1);
        assertThat(countOutbox(issue.getId())).isEqualTo(1);
    }

    private void allowRole(ProjectRole role, boolean member) {
        when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setProjectExists(true).setIsMember(member).setRole(role).build()));
    }

    private Issue createIssue() {
        return issueRepository.save(Issue.builder()
                .projectId(PROJECT_ID).issueNumber(1).issueKey("DEL-1")
                .issueType(IssueType.TASK).summary("Delete regression")
                .statusKey("TODO").priority(IssuePriority.MEDIUM)
                .reporterId(ACTOR_ID).version(1).build()).block();
    }

    private Mono<Issue> delete(UUID issueId) {
        return issueService.deleteIssue(REQUEST_ID, NODE_ID, issueId, ACTOR_ID);
    }

    private void assertUnchanged(Issue before) {
        Issue after = issueRepository.findById(before.getId()).block();
        assertThat(after).isNotNull();
        assertThat(after.getDeletedAt()).isNull();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(countHistory(before.getId())).isZero();
        assertThat(countOutbox(before.getId())).isZero();
    }

    private long countHistory(UUID issueId) {
        return databaseClient.sql("SELECT count(*) FROM taska.issue_history WHERE issue_id = :id")
                .bind("id", issueId).map(row -> row.get(0, Long.class)).one().block();
    }

    private long countOutbox(UUID issueId) {
        return databaseClient.sql("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id = :id")
                .bind("id", issueId).map(row -> row.get(0, Long.class)).one().block();
    }

    private static void assertStatus(Throwable error, DomainStatus status) {
        assertThat(error).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(status));
    }
}
