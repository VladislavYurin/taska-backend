package ru.taska.integration;

import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.springframework.data.domain.Limit;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequestBody;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.ProjectKeyResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.IssueService;
import ru.taska.transport.grpc.GrpcIssueService;

class PlanningFieldsIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private GrpcIssueService grpcIssueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueHistoryRepository issueHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = REPORTER_ID;
    private static final String REQUEST_ID = "req-planning-001";
    private static final String NODE_ID = "issue-service";

    private static final BigDecimal STORY_POINTS = BigDecimal.valueOf(5);
    private static final BigDecimal NEW_STORY_POINTS = BigDecimal.valueOf(8);
    private static final LocalDate START_DATE = LocalDate.of(2026, 9, 1);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 9, 10);
    private static final Integer ORIGINAL_ESTIMATE_MINUTES = 480;
    private static final Integer REMAINING_ESTIMATE_MINUTES = 240;

    @BeforeEach
    void setUp() {
        issueRepository.deleteAll().block();

        Mockito.when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
               .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                                                                   .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                                                                   .setIsMember(true)
                                                                   .setProjectExists(true)
                                                                   .build()));

        Mockito.when(projectServiceStub.getProjectKeyInternal(any(GetProjectKeyInternalRequest.class)))
               .thenReturn(Mono.just(ProjectKeyResponse.newBuilder()
                                                       .setProjectKey("TST")
                                                       .build()));
    }

    @DisplayName("Issue можно создать без planning fields — все поля null")
    @Test
    void createIssue_withoutPlanningFields() {
        Issue created = issueService.createIssue(
                                            REQUEST_ID, NODE_ID, UUID.randomUUID().toString(), PROJECT_ID, IssueType.TASK,
                                            "Задача без планирования", null, IssuePriority.MEDIUM, REPORTER_ID,
                                            null, null, null, null, null)
                                    .block();

        Assertions.assertThat(created).isNotNull();
        Assertions.assertThat(created.getStoryPoints()).isNull();
        Assertions.assertThat(created.getStartDate()).isNull();
        Assertions.assertThat(created.getDueDate()).isNull();
        Assertions.assertThat(created.getOriginalEstimateMinutes()).isNull();
        Assertions.assertThat(created.getRemainingEstimateMinutes()).isNull();

        Issue fetched = issueService.getIssue(REQUEST_ID, NODE_ID, created.getId(), ACTOR_USER_ID)
                                    .block()
                                    .getIssue();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getStoryPoints()).isNull();
    }

    @DisplayName("Issue можно создать с полным набором planning fields")
    @Test
    void createIssue_withValidPlanningFields() {
        Issue created = issueService.createIssue(
                                            REQUEST_ID, NODE_ID, UUID.randomUUID().toString(), PROJECT_ID, IssueType.STORY,
                                            "Задача с планированием", "Описание", IssuePriority.HIGH, REPORTER_ID,
                                            STORY_POINTS, START_DATE, DUE_DATE,
                                            ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES)
                                    .block();

        Assertions.assertThat(created).isNotNull();

        Issue fetched = issueRepository.findById(created.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getStoryPoints()).isEqualByComparingTo(STORY_POINTS);
        Assertions.assertThat(fetched.getStartDate()).isEqualTo(START_DATE);
        Assertions.assertThat(fetched.getDueDate()).isEqualTo(DUE_DATE);
        Assertions.assertThat(fetched.getOriginalEstimateMinutes()).isEqualTo(ORIGINAL_ESTIMATE_MINUTES);
        Assertions.assertThat(fetched.getRemainingEstimateMinutes()).isEqualTo(REMAINING_ESTIMATE_MINUTES);
    }

    @DisplayName("GrpcIssueService.createIssue: отрицательный storyPoints -> StatusRuntimeException INVALID_ARGUMENT, ничего не сохраняется")
    @Test
    void createIssue_rejectsNegativeStoryPoints() {
        CreateIssueRequest request = CreateIssueRequest.newBuilder()
                                                       .setHeader(Header.newBuilder()
                                                                        .setRequestId(REQUEST_ID)
                                                                        .setNodeId(NODE_ID)
                                                                        .build())
                                                       .setBody(CreateIssueRequestBody.newBuilder()
                                                                                      .setIdempotencyKey(UUID.randomUUID().toString())
                                                                                      .setProjectId(PROJECT_ID.toString())
                                                                                      .setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK)
                                                                                      .setSummary("Задача")
                                                                                      .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                                                                      .setReporterId(REPORTER_ID.toString())
                                                                                      .setStoryPoints(-1.0)
                                                                                      .build())
                                                       .build();

        StepVerifier.create(grpcIssueService.createIssue(Mono.just(request)))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                        Assertions.assertThat(((StatusRuntimeException) throwable).getStatus().getCode())
                                  .isEqualTo(Status.INVALID_ARGUMENT.getCode());
                    })
                    .verify();

        Assertions.assertThat(issueRepository.count().block()).isZero();
    }

    @DisplayName("GrpcIssueService.createIssue: startDate после dueDate -> StatusRuntimeException INVALID_ARGUMENT")
    @Test
    void createIssue_rejectsStartDateAfterDueDate() {
        CreateIssueRequest request = CreateIssueRequest.newBuilder()
                                                       .setHeader(Header.newBuilder()
                                                                        .setRequestId(REQUEST_ID)
                                                                        .setNodeId(NODE_ID)
                                                                        .build())
                                                       .setBody(CreateIssueRequestBody.newBuilder()
                                                                                      .setIdempotencyKey(UUID.randomUUID().toString())
                                                                                      .setProjectId(PROJECT_ID.toString())
                                                                                      .setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK)
                                                                                      .setSummary("Задача")
                                                                                      .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                                                                      .setReporterId(REPORTER_ID.toString())
                                                                                      .setStartDate(DUE_DATE.plusDays(5).toString())
                                                                                      .setDueDate(DUE_DATE.toString())
                                                                                      .build())
                                                       .build();

        StepVerifier.create(grpcIssueService.createIssue(Mono.just(request)))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                        Assertions.assertThat(((StatusRuntimeException) throwable).getStatus().getCode())
                                  .isEqualTo(Status.INVALID_ARGUMENT.getCode());
                    })
                    .verify();

        Assertions.assertThat(issueRepository.count().block()).isZero();
    }

    @DisplayName("UpdateIssue изменяет planning fields, история и outbox фиксируют изменение")
    @Test
    void updateIssue_changesPlanningFields_historyAndOutboxRecorded() {
        Issue created = issueService.createIssue(
                                            REQUEST_ID, NODE_ID, UUID.randomUUID().toString(), PROJECT_ID, IssueType.TASK,
                                            "Задача", null, IssuePriority.MEDIUM, REPORTER_ID,
                                            STORY_POINTS, null, null, null, null)
                                    .block();
        Assertions.assertThat(created).isNotNull();

        Issue updated = issueService.updateIssue(
                                            REQUEST_ID, NODE_ID, created.getId(), ACTOR_USER_ID,
                                            created.getSummary(), created.getDescription(), created.getPriority(),
                                            NEW_STORY_POINTS, START_DATE, DUE_DATE,
                                           ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES)
                                    .block();

        Assertions.assertThat(updated).isNotNull();
        Assertions.assertThat(updated.getStoryPoints()).isEqualByComparingTo(NEW_STORY_POINTS);
        Assertions.assertThat(updated.getStartDate()).isEqualTo(START_DATE);
        Assertions.assertThat(updated.getDueDate()).isEqualTo(DUE_DATE);

        long historyCount = issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(created.getId(), Limit.unlimited())
                                                  .count()
                                                  .block();
        Assertions.assertThat(historyCount).isGreaterThan(0);

        long outboxCount = outboxEventRepository.findAll()
                                                .filter(event -> event.getAggregateId().equals(created.getId()))
                                                .count()
                                                .block();
        Assertions.assertThat(outboxCount).isGreaterThan(0);
    }

    @DisplayName("ListIssues возвращает planning fields для нескольких задач")
    @Test
    void listIssues_returnsPlanningFields() {
        issueService.createIssue(
                            REQUEST_ID, NODE_ID, UUID.randomUUID().toString(), PROJECT_ID, IssueType.TASK,
                            "Задача 1", null, IssuePriority.MEDIUM, REPORTER_ID,
                            STORY_POINTS, START_DATE, DUE_DATE,
                            ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES)
                    .block();

        var page = issueService.searchIssues(
                                       REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                                       null, PROJECT_ID, null, null, null, null, null, 0, 20)
                               .block();

        Assertions.assertThat(page).isNotNull();
        Assertions.assertThat(page.items())
                  .isNotEmpty()
                  .allSatisfy(issue -> {
                      if (issue.getProjectId().equals(PROJECT_ID)) {
                          Assertions.assertThat(issue.getStoryPoints()).isNotNull();
                      }
                  });
    }

    @DisplayName("Существующая задача без planning fields (как после миграции) остаётся валидной для чтения")
    @Test
    void legacyIssueWithoutPlanningFields_remainsReadable() {
        Issue legacyIssue = Issue.builder()
                                 .projectId(PROJECT_ID)
                                 .issueNumber(999)
                                 .issueKey("LEGACY-999")
                                 .issueType(IssueType.TASK)
                                 .summary("Старая задача")
                                 .description("")
                                 .statusKey("TODO")
                                 .priority(IssuePriority.LOW)
                                 .reporterId(REPORTER_ID)
                                 .version(1)
                                 .storyPoints(null)
                                 .startDate(null)
                                 .dueDate(null)
                                 .originalEstimateMinutes(null)
                                 .remainingEstimateMinutes(null)
                                 .build();

        Issue saved = issueRepository.save(legacyIssue).block();
        Assertions.assertThat(saved).isNotNull();

        Issue fetched = issueRepository.findById(saved.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getStoryPoints()).isNull();
        Assertions.assertThat(fetched.getStartDate()).isNull();
    }
}