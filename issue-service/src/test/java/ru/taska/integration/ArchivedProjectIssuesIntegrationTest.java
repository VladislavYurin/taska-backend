package ru.taska.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nullable.NullableField;
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
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;
import ru.taska.service.CommentService;
import ru.taska.service.patch.IssuePatchService;
import ru.taska.service.transition.IssueTransitionService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.kafka.OutboxEventScheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class ArchivedProjectIssuesIntegrationTest extends AbstractIT {
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean GrpcProjectServiceClient projects;
    @Autowired IssueRepository repository;
    @Autowired IssueService issues;
    @Autowired ru.taska.transport.grpc.GrpcIssueService grpcIssues;
    @Autowired CommentService comments;
    @Autowired IssuePatchService patches;
    @Autowired IssueTransitionService transitions;
    @Autowired ru.taska.service.attachment.AttachmentService attachments;
    @Autowired ru.taska.service.worklog.WorklogService worklogs;
    @Autowired ru.taska.service.impl.LabelServiceImpl labels;
    @Autowired ru.taska.service.watcher.IssueWatcherServiceImpl watchers;
    @Autowired ru.taska.service.link.IssueLinkService links;
    @Autowired DatabaseClient db;
    private final UUID project=UUID.randomUUID(),actor=UUID.randomUUID();
    private Issue issue;

    @BeforeEach
    void archived() {
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                .setProjectExists(true).setIsMember(true).setProjectArchived(true).setRole(ProjectRole.PROJECT_ROLE_ADMIN).build()));
        when(projects.getProjectKeyInternal(any(),any(),any())).thenReturn(Mono.just("ARC"));
        issue=repository.save(Issue.builder().projectId(project).issueNumber(1).issueKey("ARC-"+UUID.randomUUID())
                .issueType(IssueType.TASK).summary("Kept").description("").reporterId(actor).priority(IssuePriority.MEDIUM).statusKey("TODO").version(1).build()).block();
    }

    @Test
    void readsAndBoardStillReturnArchivedProjectIssues() {
        assertThat(issues.getIssue("r","n",issue.getId(),actor).block().getIssue().getId()).isEqualTo(issue.getId());
        var boardRequest = ru.taska.api.issue.v1.ListIssuesForBoardRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder().setRequestId("r").setNodeId("n"))
                .setBody(ru.taska.api.issue.v1.ListIssuesForBoardRequestBody.newBuilder()
                        .setProjectId(project.toString()).setActorUserId(actor.toString()).setIncludeDone(true)
                        .setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK)).build();
        assertThat(grpcIssues.listIssuesForBoard(Mono.just(boardRequest)).block().getIssuesList())
                .singleElement().satisfies(row->assertThat(row.getId()).isEqualTo(issue.getId().toString()));
        assertThat(issues.listIssues("r","n",project,actor,null,null,null,0,20).block().items()).hasSize(1);
    }

    @Test
    void mutationRoutesRejectArchiveWithoutChangingIssueOrWritingEvents() {
        var emptyPatch=new IssuePatch(Optional.empty(),Optional.empty(),NullableField.absent(),NullableField.absent(),
                NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent());
        var target=repository.save(issue.toBuilder().id(null).issueNumber(2).issueKey("ARC-"+UUID.randomUUID()).build()).block();
        for (Mono<?> write:List.<Mono<?>>of(
                issues.createIssue("r","n",UUID.randomUUID().toString(),project,IssueType.TASK,"New","",IssuePriority.MEDIUM,actor,null,null,null,null,null),
                issues.updateIssue("r","n",issue.getId(),actor,"Changed","",IssuePriority.MEDIUM,null,null,null,null,null),
                issues.deleteIssue("r","n",issue.getId(),actor),issues.assignIssue("r","n",issue.getId(),actor,actor),
                patches.patchIssue("r","n",issue.getId(),actor,issue.getVersion(),emptyPatch),
                transitions.transitionIssue("r","n",issue.getId(),UUID.randomUUID(),actor,""),
                comments.addComment("r","n",issue.getId(),actor,"Comment"),
                attachments.createUploadUrl("r","n",issue.getId(),actor,"image/png",100),
                labels.createProjectLabel("r","n",new ru.taska.domain.dto.labels.LabelCommands.CreateProjectLabelRequestDto(project,"Label","#abcdef",actor)),
                worklogs.addIssueWorklog("r","n",issue.getId(),actor,new ru.taska.domain.dto.CreateWorklogDto(10,LocalDate.now(),"Work")),
                watchers.watchIssue("r","n",issue.getId(),actor,null),
                links.createIssueLink("r","n",issue.getId(),target.getId(),ru.taska.domain.entity.IssueLinkType.BLOCKS,actor))) {
            StepVerifier.create(write).expectErrorSatisfies(e -> assertThat(((DomainException)e).getDetails())
                    .containsEntry("reason","PROJECT_ARCHIVED")).verify();
        }
        assertThat(repository.findById(issue.getId()).block().getSummary()).isEqualTo("Kept");
        assertThat(db.sql("SELECT count(*) AS n FROM taska.outbox_events WHERE aggregate_id=:id").bind("id",issue.getId())
                .map((r,m)->r.get("n",Long.class)).one().block()).isZero();
    }
}
