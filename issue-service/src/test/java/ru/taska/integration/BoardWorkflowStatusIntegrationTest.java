package ru.taska.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.stream.IntStream;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.api.issue.v1.IssueBoardResponse;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.ListIssuesForBoardRequest;
import ru.taska.api.issue.v1.ListIssuesForBoardRequestBody;
import ru.taska.api.issue.v1.ListIssuesForBoardResponse;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.GrpcIssueService;
import ru.taska.transport.kafka.OutboxEventScheduler;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class BoardWorkflowStatusIntegrationTest extends AbstractIT {
    @MockitoBean
    private OutboxEventScheduler outboxScheduler;
    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectService;
    @Autowired
    private IssueRepository issues;
    @Autowired
    private GrpcIssueService grpcService;

    @Autowired private DatabaseClient db;
    private static final Instant BOARD_NOW = Instant.parse("2026-11-05T10:00:00.123456Z");
    private UUID project;
    private UUID actor;

    @BeforeEach
    void createIssuesWithCustomWorkflowKeys() {
        project = UUID.randomUUID();
        actor = UUID.randomUUID();
        int number = 0;
        for (String key : List.of("OPEN", "DONE", "CLOSED", "RELEASED")) {
            number++;
            issues.save(
                            Issue.builder()
                                    .projectId(project)
                                    .issueNumber(number)
                                    .issueKey(project + "-" + number)
                                    .summary("Board issue")
                                    .statusKey(key)
                                    .issueType(IssueType.TASK)
                                    .priority(IssuePriority.MEDIUM)
                                    .reporterId(actor)
                                    .build())
                    .block(Duration.ofSeconds(5));
        }
        when(projectService.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder().setIsMember(true)
                        .setProjectExists(true).setProjectArchived(false).setRole(ProjectRole.PROJECT_ROLE_MEMBER).build()));
    }

    static List<Integer> pageLimits() {
        return Arrays.asList(null, 1);
    }

    @ParameterizedTest
    @MethodSource("pageLimits")
    void finalKeysAreExcludedByBothSqlPathsAndIncludeDoneOverridesTheFilter(Integer limit) {
        // DONE is an active workflow key; CLOSED and RELEASED are the final categories' keys.
        assertThat(statuses(readBoard(false, List.of("CLOSED", "RELEASED"), null, limit)))
                .containsExactlyInAnyOrder("OPEN", "DONE");
        assertThat(statuses(readBoard(true, List.of("CLOSED", "RELEASED"), null, limit)))
                .containsExactlyInAnyOrder("OPEN", "DONE", "CLOSED", "RELEASED");
        assertThat(statuses(readBoard(false, List.of(), null, limit)))
                .containsExactlyInAnyOrder("OPEN", "DONE", "CLOSED", "RELEASED");
        assertThat(statuses(readBoard(false, List.of("CLOSED", "RELEASED"), "CLOSED", limit)))
                .isEmpty();
    }

    private ListIssuesForBoardResponse readBoard(boolean includeDone, List<String> doneKeys, String status, Integer limit) {
        var body = ListIssuesForBoardRequestBody.newBuilder().setProjectId(project.toString())
                .setActorUserId(actor.toString()).setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK)
                .setIncludeDone(includeDone).addAllDoneStatusKeys(doneKeys);
        if (status != null) {
            body.setStatusKey(status);
        }
        if (limit != null) {
            body.setPageSizePerColumn(limit);
        }
        var request =
                ListIssuesForBoardRequest.newBuilder()
                        .setHeader(
                                Header.newBuilder()
                                        .setRequestId("board-workflow-status")
                                        .setNodeId("test"))
                        .setBody(body)
                        .build();
        return grpcService.listIssuesForBoard(Mono.just(request)).block(Duration.ofSeconds(10));
    }

    private List<String> statuses(ListIssuesForBoardResponse response) {
        return response.getIssuesList().stream().map(issue -> issue.getStatusKey()).toList();
    }

    UUID doneId(int number) {
        return UUID.fromString(
                project.toString().substring(0, 24) + String.format("%012x", number + 1000));
    }

    void seedDone(int count, String status) {
        db.sql(
                        """
                        INSERT INTO taska.issues(id,project_id,issue_number,issue_key,summary,status_key,issue_type,priority,reporter_id,created_at,updated_at)
                        SELECT (substr(:project::text,1,24)||lpad(to_hex(i+1000),12,'0'))::uuid,:project,1000+i,
                          :project::text||'-DN-'||i,'Board regression',:status,'TASK','MEDIUM',:actor,
                          :at::timestamptz-(150-i)*interval '1 second',:at::timestamptz-(150-i)*interval '1 day'
                        FROM generate_series(1,:count) i
                        """)
                .bind("project", project)
                .bind("actor", actor)
                .bind("status", status)
                .bind("at", BOARD_NOW.atOffset(ZoneOffset.UTC))
                .bind("count", count)
                .then()
                .block(Duration.ofSeconds(10));
    }

    ListIssuesForBoardRequestBody.Builder body() {
        return ListIssuesForBoardRequestBody.newBuilder()
                .setProjectId(project.toString())
                .setActorUserId(actor.toString())
                .setIssueTypeKey("TASK")
                .setIncludeDone(true)
                .addDoneStatusKeys("FINISHED");
    }

    Timestamp stamp(Instant at) {
        return Timestamp.newBuilder()
                .setSeconds(at.getEpochSecond())
                .setNanos(at.getNano())
                .build();
    }

    ListIssuesForBoardResponse read(ListIssuesForBoardRequestBody.Builder body) {
        return grpcService
                .listIssuesForBoard(
                        Mono.just(
                                ListIssuesForBoardRequest.newBuilder()
                                        .setHeader(
                                                Header.newBuilder()
                                                        .setRequestId("board-done-order")
                                                        .setNodeId("test"))
                                        .setBody(body)
                                        .build()))
                .block(Duration.ofSeconds(10));
    }

    List<String> expectedDone(int lowest) {
        return IntStream.rangeClosed(lowest, 150)
                .map(i -> 150 + lowest - i)
                .mapToObj(i -> doneId(i).toString())
                .toList();
    }

    @Test
    void done150RowsUseUpdatedDescendingForInitialAndEveryCursorPage() {
        seedDone(150, "FINISHED");
        var initial = read(body().setPageSizePerColumn(50));
        assertThat(initial.getBoardReadVersion()).isEqualTo(1);
        var rows =
                initial.getIssuesList().stream()
                        .filter(i -> i.getStatusKey().equals("FINISHED"))
                        .toList();
        assertThat(rows)
                .extracting(IssueBoardResponse::getId)
                .containsExactlyElementsOf(expectedDone(101));
        assertThat(rows).allSatisfy(i -> assertThat(i.getStatusTotalCount()).isEqualTo(150));
        var request = body().setStatusKey("FINISHED").setPageSizePerColumn(47);
        List<String> all = new ArrayList<>();
        ListIssuesForBoardResponse page;
        do {
            page = read(request);
            assertThat(page.getTotalCount()).isEqualTo(150);
            all.addAll(page.getIssuesList().stream().map(IssueBoardResponse::getId).toList());
            var last = page.getIssues(page.getIssuesCount() - 1);
            request.setAfterUpdatedAt(last.getUpdatedAt()).setAfterId(last.getId());
        } while (page.getHasMore());
        assertThat(all).containsExactlyElementsOf(expectedDone(1)).doesNotHaveDuplicates();
        var exhausted = read(request);
        assertThat(exhausted.getIssuesList()).isEmpty();
        assertThat(exhausted.getTotalCount()).isEqualTo(150);
        assertThat(exhausted.getHasMore()).isFalse();
    }

    @Test
    void doneSinceIsInclusiveAndLeavesActiveLiteralDoneAndOutsideStatusesUntouched() {
        seedDone(150, "FINISHED");
        Instant since = BOARD_NOW.minus(java.time.Duration.ofDays(14));
        var first = read(body().setPageSizePerColumn(200).setDoneSince(stamp(since)));
        var done =
                first.getIssuesList().stream()
                        .filter(i -> i.getStatusKey().equals("FINISHED"))
                        .toList();
        assertThat(done)
                .extracting(IssueBoardResponse::getId)
                .containsExactlyElementsOf(expectedDone(136));
        assertThat(done).allSatisfy(i -> assertThat(i.getStatusTotalCount()).isEqualTo(15));
        assertThat(first.getIssuesList())
                .extracting(IssueBoardResponse::getStatusKey)
                .contains("OPEN", "DONE", "CLOSED", "RELEASED");
        var page =
                read(
                        body().setStatusKey("FINISHED")
                                .setPageSizePerColumn(10)
                                .setDoneSince(stamp(since)));
        assertThat(page.getTotalCount()).isEqualTo(15);
        assertThat(page.getHasMore()).isTrue();
        var last = page.getIssues(9);
        var second =
                read(
                        body().setStatusKey("FINISHED")
                                .setPageSizePerColumn(10)
                                .setDoneSince(stamp(since))
                                .setAfterUpdatedAt(last.getUpdatedAt())
                                .setAfterId(last.getId()));
        assertThat(second.getTotalCount()).isEqualTo(15);
        assertThat(second.getIssuesCount()).isEqualTo(5);
        assertThat(second.getHasMore()).isFalse();
        assertThat(
                        read(body().setPageSizePerColumn(200)
                                        .setIncludeDone(false)
                                        .setDoneSince(stamp(since)))
                                .getIssuesList())
                .extracting(IssueBoardResponse::getStatusKey)
                .contains("DONE")
                .doesNotContain("FINISHED");
    }

    @Test
    void equalUpdatedTimestampsUseUnsignedUuidDescendingWithoutRepeats() {
        seedDone(150, "FINISHED");
        db.sql(
                        "UPDATE taska.issues SET updated_at=:at WHERE project_id=:p AND"
                            + " status_key='FINISHED'")
                .bind("p", project)
                .bind("at", BOARD_NOW.atOffset(ZoneOffset.UTC))
                .then()
                .block();
        var request = body().setStatusKey("FINISHED").setPageSizePerColumn(100);
        var first = read(request);
        var last = first.getIssues(99);
        var second = read(request.setAfterUpdatedAt(last.getUpdatedAt()).setAfterId(last.getId()));
        List<String> all =
                new ArrayList<>(
                        first.getIssuesList().stream().map(IssueBoardResponse::getId).toList());
        all.addAll(second.getIssuesList().stream().map(IssueBoardResponse::getId).toList());
        assertThat(all).containsExactlyElementsOf(expectedDone(1)).doesNotHaveDuplicates();
        assertThat(second.getHasMore()).isFalse();
    }

    @Test
    void wrongCursorKindAndInvalidDoneDateAreRejectedBeforeQuery() {
        var at = stamp(BOARD_NOW);
        String id = UUID.randomUUID().toString();
        var bad =
                List.of(
                        body().setStatusKey("FINISHED").setAfterCreatedAt(at).setAfterId(id),
                        body().setStatusKey("OPEN").setAfterUpdatedAt(at).setAfterId(id),
                        body().setStatusKey("FINISHED")
                                .setAfterUpdatedAt(at)
                                .setAfterCreatedAt(at)
                                .setAfterId(id),
                        body().setStatusKey("FINISHED").setAfterUpdatedAt(at),
                        body().setDoneSince(Timestamp.newBuilder().setSeconds(253402300800L)));
        for (var request : bad) {
            var error =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            io.grpc.StatusRuntimeException.class, () -> read(request));
            assertThat(error.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT);
        }
    }
}
