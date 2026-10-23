package ru.taska.integration;

import org.junit.jupiter.api.BeforeEach;
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

    private UUID project;
    private UUID actor;

    @BeforeEach
    void createIssuesWithCustomWorkflowKeys() {
        project = UUID.randomUUID();
        actor = UUID.randomUUID();
        int number = 0;
        for (String key : List.of("OPEN", "DONE", "CLOSED", "RELEASED")) {
            number++;
            issues.save(Issue.builder().projectId(project).issueNumber(number).issueKey(project + "-" + number)
                    .summary("Board issue").statusKey(key).issueType(IssueType.TASK).priority(IssuePriority.MEDIUM)
                    .reporterId(actor).build()).block(Duration.ofSeconds(5));
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
        assertThat(statuses(readBoard(false, List.of("CLOSED", "RELEASED"), "CLOSED", limit))).isEmpty();
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
        var request = ListIssuesForBoardRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("board-workflow-status").setNodeId("test"))
                .setBody(body).build();
        return grpcService.listIssuesForBoard(Mono.just(request)).block(Duration.ofSeconds(10));
    }

    private List<String> statuses(ListIssuesForBoardResponse response) {
        return response.getIssuesList().stream().map(issue -> issue.getStatusKey()).toList();
    }
}
