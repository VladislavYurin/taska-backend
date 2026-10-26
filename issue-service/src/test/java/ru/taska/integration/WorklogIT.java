package ru.taska.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.IssueType;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.entity.Worklog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorklogMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.worklog.WorklogService;
import ru.taska.transport.grpc.profile.GrpcAuthServiceClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Интеграционные тесты для worklog time-tracking функциональности.
 *
 * <p>Построены по аналогии с {@link ru.taska.integration.AttachmentIT}: поднимается реальный
 * Spring-контекст с реальной БД (через AbstractIT), внешний grpc-стаб project-service мокается,
 * а {@link WorklogService} вызывается напрямую (минуя transport/grpc слой).</p>
 *
 * <p>Предполагается, что {@code IssueHistoryService} зарегистрирован в контексте как единственный
 * bean соответствующего типа — если сигнатура {@code saveIssueHistory} в реальном коде отличается
 * от использованной здесь (String, String, UUID, UUID, IssueEventType, payload), поправьте матчеры
 * в тесте {@link #addIssueWorklog_shouldRollbackWorklogAndIssueChanges_whenHistorySaveFails()}.</p>
 */
class WorklogIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @MockitoBean
    private GrpcAuthServiceClient grpcAuthServiceClient;

    @MockitoSpyBean
    private IssueHistoryService issueHistoryService;

    @Autowired
    private WorklogService worklogService;

    @Autowired
    private WorklogRepository worklogRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IssueHistoryRepository issueHistoryRepository;

    @Autowired
    private IssueProperties issueProperties;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private WorklogMapper worklogMapper;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final String REQUEST_ID = "req-worklog-it";
    private static final String NODE_ID = "issue-service";
    private static final int INITIAL_REMAINING_MINUTES = 120;

    private UUID issueId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll()
                .then(issueHistoryRepository.deleteAll())
                .then(worklogRepository.deleteAll())
                .then(issueRepository.deleteAll())
                .block();

        Issue issue = Issue.builder()
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("WLG-1")
                .summary("Worklog test issue")
                .statusKey("TODO")
                .issueType(IssueType.TASK)
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .timeSpentMinutes(0)
                .remainingEstimateMinutes(INITIAL_REMAINING_MINUTES)
                .build();
        issueId = issueRepository.save(issue).block().getId();

        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        Mockito.lenient().when(grpcAuthServiceClient.getUserProfiles(Mockito.anySet(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(Map.of()));
    }

    @AfterEach
    void resetSpies() {
        Mockito.reset(issueHistoryService);
    }

    private void stubProjectRole(ProjectRole role) {
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(role)
                        .setIsMember(true)
                        .setProjectExists(true).setProjectArchived(false)
                        .build()));
    }

    private Worklog createWorklogViaService(int spentMinutes, LocalDate workDate, String comment) {
        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        CreateWorklogDto dto = new CreateWorklogDto(spentMinutes, workDate, comment);
        return worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, MEMBER_ID, dto).block();
    }

    // ===== AddIssueWorklog =====

    @Test
    void addIssueWorklog_shouldPersistAndAggregateTimeSpentAndDecreaseRemainingEstimate() {
        CreateWorklogDto dto = new CreateWorklogDto(45, LocalDate.now(), "Initial work");

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, MEMBER_ID, dto))
                .assertNext(worklog -> {
                    Assertions.assertEquals(issueId, worklog.getIssueId());
                    Assertions.assertEquals(PROJECT_ID, worklog.getProjectId());
                    Assertions.assertEquals(MEMBER_ID, worklog.getAuthorUserId());
                    Assertions.assertEquals(45, worklog.getSpentMinutes());
                    Assertions.assertEquals("Initial work", worklog.getComment());
                    Assertions.assertNotNull(worklog.getVersion());
                })
                .verifyComplete();

        Issue updatedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertNotNull(updatedIssue);
        Assertions.assertEquals(45, updatedIssue.getTimeSpentMinutes());
        Assertions.assertEquals(INITIAL_REMAINING_MINUTES - 45, updatedIssue.getRemainingEstimateMinutes());

        Assertions.assertEquals(1L, worklogRepository.count().block());
        Assertions.assertEquals(1L, issueHistoryRepository.count().block());
        Assertions.assertEquals(1L, outboxEventRepository.count().block());
    }

    @Test
    void addIssueWorklog_shouldClampRemainingEstimateAtZero_whenSpentExceedsRemaining() {
        CreateWorklogDto dto = new CreateWorklogDto(INITIAL_REMAINING_MINUTES + 50, LocalDate.now(), null);

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, MEMBER_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Issue updatedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertNotNull(updatedIssue);
        Assertions.assertEquals(0, updatedIssue.getRemainingEstimateMinutes());
    }

    @Test
    void addIssueWorklog_shouldRejectNonPositiveSpentMinutes_andPersistNothing() {
        CreateWorklogDto dto = new CreateWorklogDto(0, LocalDate.now(), null);

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.INVALID_ARGUMENT, ((DomainException) error).getStatus());
                })
                .verify();

        Assertions.assertEquals(0L, worklogRepository.count().block());
        Issue unchangedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertEquals(0, unchangedIssue.getTimeSpentMinutes());
    }

    @Test
    void addIssueWorklog_shouldRejectWorkDateTooFarInFuture_andPersistNothing() {
        LocalDate tooFar = LocalDate.now().plusDays(issueProperties.maxFutureDays() + 30L);
        CreateWorklogDto dto = new CreateWorklogDto(10, tooFar, null);

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.INVALID_ARGUMENT, ((DomainException) error).getStatus());
                })
                .verify();

        Assertions.assertEquals(0L, worklogRepository.count().block());
    }

    @Test
    void addIssueWorklog_shouldRejectWhenActorIsViewer_andPersistNothing() {
        stubProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);
        CreateWorklogDto dto = new CreateWorklogDto(10, LocalDate.now(), null);

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, ((DomainException) error).getStatus());
                })
                .verify();

        Assertions.assertEquals(0L, worklogRepository.count().block());
    }

    @Test
    void addIssueWorklog_shouldFailWhenIssueNotFound() {
        UUID missingIssueId = UUID.randomUUID();
        CreateWorklogDto dto = new CreateWorklogDto(10, LocalDate.now(), null);

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, missingIssueId, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, ((DomainException) error).getStatus());
                })
                .verify();
    }

    // ===== ListIssueWorklogs =====

    @Test
    void listIssueWorklog_shouldAllowViewerAndExcludeSoftDeletedEntries() {
        Worklog toDelete = createWorklogViaService(20, LocalDate.now().minusDays(1), "old");
        Worklog kept = createWorklogViaService(15, LocalDate.now(), "new");

        stubProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);
        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, issueId, toDelete.getId(), ADMIN_ID))
                .expectNextCount(1)
                .verifyComplete();

        stubProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);
        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 0, 20))
                .assertNext(page -> {
                    Assertions.assertEquals(1, page.items().size());
                    Assertions.assertEquals(1, page.totalCount());
                    Assertions.assertEquals(kept.getId(), page.items().get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void worklogPagesShouldSortTimestampTiesAndOnlyFetchSelectedAuthorsIncludingFormerMembers() {
        Instant created = Instant.parse("2026-09-01T10:00:00Z");
        LocalDate date = LocalDate.of(2026, 9, 1);
        insertWorklog(1, issueId, OTHER_MEMBER_ID, date, created, false);
        insertWorklog(2, issueId, MEMBER_ID, date, created, false);
        insertWorklog(3, issueId, MEMBER_ID, date, created, false);
        insertWorklog(4, issueId, OTHER_MEMBER_ID, date, created.minusSeconds(1), false);
        insertWorklog(5, issueId, OTHER_MEMBER_ID, date.minusDays(1), created.plusSeconds(100), false);
        insertWorklog(6, issueId, ADMIN_ID, date.plusDays(1), created, true);
        insertWorklog(7, UUID.randomUUID(), ADMIN_ID, date.plusDays(1), created, false);
        // Read access is preserved for archived projects. Former authors need not be current members.
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(ProjectRole.PROJECT_ROLE_VIEWER).setIsMember(true)
                        .setProjectExists(true).setProjectArchived(true).build()));
        Mockito.when(grpcAuthServiceClient.getUserProfiles(Set.of(MEMBER_ID), REQUEST_ID, NODE_ID))
                .thenReturn(Mono.just(Map.of(MEMBER_ID, new UserSummary(MEMBER_ID, "Current author", null))));
        var first = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 0, 2).block();
        Assertions.assertEquals(List.of(id(3), id(2)), first.items().stream().map(Worklog::getId).toList());
        Assertions.assertEquals(5, first.totalCount());
        Assertions.assertTrue(first.hasMore());
        Mockito.verify(grpcAuthServiceClient).getUserProfiles(Set.of(MEMBER_ID), REQUEST_ID, NODE_ID);
        Mockito.clearInvocations(grpcAuthServiceClient);

        Mockito.when(grpcAuthServiceClient.getUserProfiles(Set.of(OTHER_MEMBER_ID), REQUEST_ID, NODE_ID))
                .thenReturn(Mono.just(Map.of(OTHER_MEMBER_ID, new UserSummary(OTHER_MEMBER_ID, "Former member", null))));
        var second = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 1, 2).block();
        Assertions.assertEquals(List.of(id(1), id(4)), second.items().stream().map(Worklog::getId).toList());
        Assertions.assertTrue(second.hasMore());
        var proto = worklogMapper.toListResponse(second);
        Assertions.assertEquals("Former member", proto.getWorklogs(0).getAuthor().getDisplayName());
        Mockito.verify(grpcAuthServiceClient).getUserProfiles(Set.of(OTHER_MEMBER_ID), REQUEST_ID, NODE_ID);

        var last = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 2, 2).block();
        Assertions.assertEquals(List.of(id(5)), last.items().stream().map(Worklog::getId).toList());
        Assertions.assertEquals(5, last.totalCount());
        Assertions.assertFalse(last.hasMore());
    }

    @Test
    void worklogPagesShouldEnforceLimitAndKeepCountOnEmptyPageWithoutOverflow() {
        for (int i = 1; i <= 101; i++) {
            insertWorklog(i, issueId, MEMBER_ID, LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T10:00:00Z"), false);
        }
        var first = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 0, 100).block();
        Assertions.assertEquals(100, first.items().size());
        Assertions.assertEquals(101, first.totalCount());
        Assertions.assertTrue(first.hasMore());
        var last = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, 1, 100).block();
        Assertions.assertEquals(1, last.items().size());
        Assertions.assertFalse(last.hasMore());
        Mockito.clearInvocations(grpcAuthServiceClient);
        var empty = worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, VIEWER_ID, Integer.MAX_VALUE, 100).block();
        Assertions.assertTrue(empty.items().isEmpty());
        Assertions.assertEquals(101, empty.totalCount());
        Assertions.assertFalse(empty.hasMore());
        Mockito.verifyNoInteractions(grpcAuthServiceClient);
    }

    @Test
    void worklogListShouldRejectNonMembersBeforeProfileFetch() {
        insertWorklog(1, issueId, MEMBER_ID, LocalDate.now(), Instant.now(), false);
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true)
                        .setIsMember(false).build()));
        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, issueId, OTHER_MEMBER_ID, 0, 20))
                .expectErrorSatisfies(error -> Assertions.assertEquals(DomainStatus.PERMISSION_DENIED,
                        ((DomainException) error).getStatus()))
                .verify();
        Mockito.verifyNoInteractions(grpcAuthServiceClient);
    }

    private UUID id(int suffix) {
        return new UUID(0, suffix);
    }

    private void insertWorklog(int suffix, UUID targetIssue, UUID author, LocalDate date, Instant created, boolean deleted) {
        var statement = databaseClient.sql("""
                INSERT INTO taska.issue_worklogs
                (id, issue_id, project_id, author_user_id, spent_minutes, work_date, created_at, updated_at, deleted_at)
                VALUES (:id, :issue, :project, :author, 5, :date, :created, :created,
                        CASE WHEN :deleted THEN :created ELSE NULL END)
                """).bind("id", id(suffix)).bind("issue", targetIssue).bind("project", PROJECT_ID)
                .bind("author", author).bind("date", date).bind("created", created).bind("deleted", deleted);
        statement.fetch().rowsUpdated().block();
    }

    // ===== UpdateIssueWorklog =====

    @Test
    void updateIssueWorklog_authorShouldUpdateOwnWorklogAndRecalculateTimeSpent() {
        Worklog created = createWorklogViaService(30, LocalDate.now(), "first pass");

        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        UpdateWorklogDto dto = new UpdateWorklogDto(50, null, "updated comment");

        StepVerifier.create(worklogService.updateIssueWorklog(
                        REQUEST_ID, NODE_ID, issueId, created.getId(), MEMBER_ID, dto))
                .assertNext(worklog -> {
                    Assertions.assertEquals(50, worklog.getSpentMinutes());
                    Assertions.assertEquals("updated comment", worklog.getComment());
                })
                .verifyComplete();

        Issue updatedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertEquals(50, updatedIssue.getTimeSpentMinutes());
        Assertions.assertEquals(INITIAL_REMAINING_MINUTES - 50, updatedIssue.getRemainingEstimateMinutes());
    }

    @Test
    void updateIssueWorklog_shouldRejectNonAuthorMemberUpdatingOthersWorklog() {
        Worklog created = createWorklogViaService(20, LocalDate.now(), null);

        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        UpdateWorklogDto dto = new UpdateWorklogDto(99, null, null);

        StepVerifier.create(worklogService.updateIssueWorklog(
                        REQUEST_ID, NODE_ID, issueId, created.getId(), OTHER_MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, ((DomainException) error).getStatus());
                })
                .verify();

        Worklog stillActive = worklogRepository.findActiveById(created.getId()).block();
        Assertions.assertNotNull(stillActive);
        Assertions.assertEquals(20, stillActive.getSpentMinutes());
    }

    @Test
    void updateIssueWorklog_adminShouldUpdateAnyWorklog() {
        Worklog created = createWorklogViaService(25, LocalDate.now(), null);

        stubProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);
        UpdateWorklogDto dto = new UpdateWorklogDto(40, null, null);

        StepVerifier.create(worklogService.updateIssueWorklog(
                        REQUEST_ID, NODE_ID, issueId, created.getId(), ADMIN_ID, dto))
                .assertNext(worklog -> Assertions.assertEquals(40, worklog.getSpentMinutes()))
                .verifyComplete();
    }

    // ===== DeleteIssueWorklog =====

    @Test
    void deleteIssueWorklog_authorShouldSoftDeleteOwnWorklogAndRestoreRemainingEstimate() {
        Worklog created = createWorklogViaService(40, LocalDate.now(), null);

        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, issueId, created.getId(), MEMBER_ID))
                .assertNext(deleted -> Assertions.assertNotNull(deleted.getDeletedAt()))
                .verifyComplete();

        Worklog activeLookup = worklogRepository.findActiveById(created.getId()).block();
        Assertions.assertNull(activeLookup);

        Worklog rawLookup = worklogRepository.findById(created.getId()).block();
        Assertions.assertNotNull(rawLookup);
        Assertions.assertNotNull(rawLookup.getDeletedAt());

        Issue updatedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertEquals(0, updatedIssue.getTimeSpentMinutes());
        Assertions.assertEquals(INITIAL_REMAINING_MINUTES, updatedIssue.getRemainingEstimateMinutes());
    }

    @Test
    void deleteIssueWorklog_shouldRejectNonAuthorMemberDeletingOthersWorklog_andKeepWorklogActive() {
        Worklog created = createWorklogViaService(15, LocalDate.now(), null);

        stubProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        StepVerifier.create(worklogService.deleteIssueWorklog(
                        REQUEST_ID, NODE_ID, issueId, created.getId(), OTHER_MEMBER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertTrue(error instanceof DomainException);
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, ((DomainException) error).getStatus());
                })
                .verify();

        Worklog stillActive = worklogRepository.findActiveById(created.getId()).block();
        Assertions.assertNotNull(stillActive);
        Assertions.assertNull(stillActive.getDeletedAt());
    }

    // ===== Rollback on history/outbox failure =====

    @Test
    void addIssueWorklog_shouldRollbackWorklogAndIssueChanges_whenHistorySaveFails() {
        IssueHistoryService historySpy =
                AopTestUtils.getUltimateTargetObject(issueHistoryService);

        Mockito.doReturn(Mono.error(new RuntimeException("simulated history failure")))
                .when(historySpy)
                .saveIssueHistory(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueEventType.class),
                        Mockito.any(JsonNode.class)
                );

        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        StepVerifier.create(
                        worklogService.addIssueWorklog(
                                REQUEST_ID,
                                NODE_ID,
                                issueId,
                                MEMBER_ID,
                                dto
                        )
                )
                .expectErrorMatches(error ->
                        error instanceof RuntimeException
                                && "simulated history failure".equals(error.getMessage()))
                .verify();

        Assertions.assertEquals(0L, worklogRepository.count().block());
        Assertions.assertEquals(0L, outboxEventRepository.count().block());
        Assertions.assertEquals(0L, issueHistoryRepository.count().block());

        Issue unchangedIssue = issueRepository.findActiveById(issueId).block();
        Assertions.assertEquals(0, unchangedIssue.getTimeSpentMinutes());
        Assertions.assertEquals(
                INITIAL_REMAINING_MINUTES,
                unchangedIssue.getRemainingEstimateMinutes()
        );
    }
}
