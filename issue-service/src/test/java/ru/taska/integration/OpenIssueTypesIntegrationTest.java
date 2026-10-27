package ru.taska.integration;

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
import ru.taska.domain.dto.IssuePatch;
import ru.taska.domain.dto.ProjectInfo;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;
import ru.taska.service.patch.IssuePatchService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.workflow.GrpcWorkflowServiceClient;
import ru.taska.transport.kafka.OutboxEventScheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nullable.NullableField;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class OpenIssueTypesIntegrationTest extends AbstractIT {
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean GrpcWorkflowServiceClient workflows;
    @Autowired IssueService issues;
    @Autowired IssuePatchService patches;
    @Autowired IssueRepository repository;
    @Autowired IssueMapper mapper;
    @Autowired DatabaseClient db;
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
    private static final IssueType REQUEST = IssueType.valueOf("REQUEST");

    @BeforeEach
    void projectRegistry() {
        when(projects.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(
                CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true)
                        .setRole(ProjectRole.PROJECT_ROLE_ADMIN).setProjectArchived(false).build()));
        when(projects.listIssueTypes(any(), any(), eq(project), eq(actor)))
                .thenReturn(Mono.just(List.of(REQUEST, IssueType.BUG)));
        when(projects.listIssueTypeDetails(any(), any(), eq(project), eq(actor))).thenReturn(Mono.just(
                List.of("REQUEST","BUG").stream().map(key -> ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder()
                        .setCode(key).setGeneration(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).build()).toList()));
        doCallRealMethod().when(projects).requireIssueType(any(), any(), any(), any(), any());
        when(projects.getProjectKeyInternal(any(), any(), eq(project))).thenReturn(Mono.just("REQ" + project.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)));
        when(projects.getProjectInfoBatchInternal(any(), any(), any())).thenReturn(Mono.just(
                Map.of(project.toString(), new ProjectInfo(project.toString(), "REQ", "Requests"))));
        when(workflows.requireStatusForType(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void customTypeRoundTripsThroughDatabaseSearchBoardSnapshotsHistoryAndIdempotency() {
        String key = UUID.randomUUID().toString();
        Issue created = create(REQUEST, key).block();
        assertThat(repository.findById(created.getId()).block().getIssueType()).isEqualTo(REQUEST);
        assertThat(mapper.toIssueProto(created).getIssueTypeKey()).isEqualTo("REQUEST");
        assertThat(mapper.toIssueProto(created).getIssueType()).isEqualTo(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_UNSPECIFIED);
        assertThat(mapper.toIssueValidateSnapshotProto(created).getIssueTypeKey()).isEqualTo("REQUEST");
        assertThat(issues.getIssue("r", "n", created.getId(), actor).block().getIssue().getIssueType()).isEqualTo(REQUEST);
        var found = issues.searchIssues("r", "n", actor, null, project, null, null, null, null, REQUEST, 0, 20).block();
        assertThat(found.items()).singleElement().satisfies(i -> assertThat(mapper.toIssueShortProto(i).getIssueTypeKey()).isEqualTo("REQUEST"));
        assertThat(issues.listIssueBoard("r", "n", actor, project, null, null, REQUEST, true, List.of("DONE"), List.of(), 10, null, null).block())
                .singleElement().satisfies(i -> assertThat(i.getIssueTypeKey()).isEqualTo("REQUEST"));
        assertThat(issues.searchIssues("r", "n", actor, null, project, null, null, null, null, IssueType.valueOf("NOPE"), 0, 20).block().items()).isEmpty();
        assertThat(issues.listIssueBoard("r", "n", actor, project, null, null, IssueType.valueOf("NOPE"), true, List.of(), List.of(), null, null, null).block()).isEmpty();
        assertThat(jsonValue("issue_history", "issue_id", created.getId(), "issueType")).isEqualTo("REQUEST");
        assertThat(jsonValue("outbox_events", "aggregate_id", created.getId(), "issueType")).isEqualTo("REQUEST");
        // A retry returns the committed response even if the registry is unavailable later.
        when(projects.listIssueTypeDetails(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        assertThat(create(REQUEST, key).block().getId()).isEqualTo(created.getId());
        verify(projects, times(1)).listIssueTypeDetails("r", "n", project, actor);
    }

    @Test
    void rejectsUnregisteredCustomAndDisabledBuiltinBeforeAllocatingIssueNumber() {
        for (var type : List.of(IssueType.valueOf("NOPE"), IssueType.TASK)) {
            StepVerifier.create(create(type, UUID.randomUUID().toString())).expectErrorSatisfies(e -> {
                assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                assertThat(e.getMessage()).contains(type.name(), "not defined in this project");
            }).verify();
        }
        verify(projects, never()).getProjectKeyInternal(any(), any(), any());
    }

    @Test
    void typePatchChecksRegistryAndWorkflowAndRecordsTypeOnlyChange() {
        Issue created = create(REQUEST, UUID.randomUUID().toString()).block();
        StepVerifier.create(patches.patchIssue("r", "n", created.getId(), actor, created.getVersion(), patch(IssueType.valueOf("NOPE"))))
                .expectErrorSatisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT)).verify();
        when(workflows.requireStatusForType(any(), any(), any(), any())).thenReturn(Mono.error(
                new DomainException(DomainStatus.FAILED_PRECONDITION, "Current status is not available for the target issue type")));
        StepVerifier.create(patches.patchIssue("r", "n", created.getId(), actor, created.getVersion(), patch(IssueType.BUG)))
                .expectError(DomainException.class).verify();
        assertThat(repository.findById(created.getId()).block().getIssueType()).isEqualTo(REQUEST);
        when(workflows.requireStatusForType(any(), any(), any(), any())).thenReturn(Mono.empty());
        var changed = patches.patchIssue("r", "n", created.getId(), actor, created.getVersion(), patch(IssueType.BUG)).block();
        assertThat(changed.issue().getIssueType()).isEqualTo(IssueType.BUG);
        assertThat(changed.issue().getVersion()).isEqualTo(created.getVersion() + 1);
        assertThat(jsonValue("issue_history", "issue_id", created.getId(), "newIssueType")).isEqualTo("BUG");
        assertThat(jsonValue("outbox_events", "aggregate_id", created.getId(), "oldIssueType")).isEqualTo("REQUEST");
        var restored = patches.patchIssue("r", "n", created.getId(), actor, changed.issue().getVersion(), patch(REQUEST)).block();
        assertThat(repository.findById(restored.issue().getId()).block().getIssueType()).isEqualTo(REQUEST);
    }

    @Test
    void typeAndHistoryRollBackTogetherWhenOutboxInsertFails() {
        Issue created = create(REQUEST, UUID.randomUUID().toString()).block();
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT type_patch_test CHECK (payload->>'newIssueType' IS NULL)")
                .fetch().rowsUpdated().block();
        try {
            StepVerifier.create(patches.patchIssue("r", "n", created.getId(), actor, created.getVersion(), patch(IssueType.BUG)))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(repository.findById(created.getId()).block().getIssueType()).isEqualTo(REQUEST);
            assertThat(jsonValue("issue_history", "issue_id", created.getId(), "newIssueType")).isNull();
        } finally {
            db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT type_patch_test").fetch().rowsUpdated().block();
        }
    }

    @Test
    void databaseAcceptsOpenKeysAndRejectsMalformedValues() {
        Issue created = create(REQUEST, UUID.randomUUID().toString()).block();
        StepVerifier.create(db.sql("UPDATE taska.issues SET issue_type='bad-type' WHERE id=:id")
                        .bind("id", created.getId()).fetch().rowsUpdated())
                .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
        assertThat(repository.findById(created.getId()).block().getIssueType()).isEqualTo(REQUEST);
        String longest = "A".repeat(32);
        db.sql("UPDATE taska.issues SET issue_type=:key WHERE id=:id").bind("key", longest)
                .bind("id", created.getId()).fetch().rowsUpdated().block();
        assertThat(repository.findById(created.getId()).block().getIssueType().name()).isEqualTo(longest);
    }

    private Mono<Issue> create(IssueType type, String key) {
        return issues.createIssue("r", "n", key, project, type, "Business request", "", IssuePriority.MEDIUM,
                actor, null, null, null, null, null);
    }

    private IssuePatch patch(IssueType type) {
        return new IssuePatch(Optional.empty(), Optional.empty(), NullableField.absent(), NullableField.absent(),
                NullableField.absent(), NullableField.absent(), NullableField.absent(), NullableField.absent(),
                NullableField.absent(), Optional.of(type));
    }

    private String jsonValue(String table, String column, UUID id, String field) {
        return db.sql("SELECT payload->>:field AS value FROM taska." + table + " WHERE " + column + "=:id AND jsonb_exists(payload, :field) LIMIT 1")
                .bind("id", id).bind("field", field).map((r, m) -> Optional.ofNullable(r.get("value", String.class))).one().blockOptional()
                .flatMap(v -> v).orElse(null);
    }
}
