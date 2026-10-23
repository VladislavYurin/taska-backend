package ru.taska.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.*;
import ru.taska.mapper.ProjectMapper;
import ru.taska.transport.grpc.GrpcProjectServiceClient;

class ProjectStatsServiceTest {
    private final GrpcProjectServiceClient projects = mock(GrpcProjectServiceClient.class);
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectStub =
            mock(ReactorProjectServiceGrpc.ReactorProjectServiceStub.class);
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueStub =
            mock(ReactorIssueServiceGrpc.ReactorIssueServiceStub.class);
    private final GatewayContext context = new GatewayContext("req", "gateway",
            GatewayUserContext.builder().userId("actor").build());
    private ProjectStatsService service;

    @BeforeEach
    void setUp() {
        var props = new GrpcClientProperties.Service("localhost", 1, Duration.ofSeconds(2));
        service = new ProjectStatsService(projects, projectStub, issueStub,
                new GrpcClientProperties(props, props, props, props, props, props), new ProjectMapper());
        lenient().when(projectStub.withDeadlineAfter(anyLong(), any())).thenReturn(projectStub);
        lenient().when(issueStub.withDeadlineAfter(anyLong(), any())).thenReturn(issueStub);
    }

    @Test
    void nineProjectsUseOneBatchEachAndKeepCountsRolesAndMissingAvatars() {
        var ids = IntStream.range(0, 9).mapToObj(i -> "project-" + i).toList();
        givenProjects(ids);
        var member = ProjectMemberDetailsResponse.newBuilder().setUserId("member")
                .setRole(ProjectRole.PROJECT_ROLE_VIEWER).setDisplayName("User").setEmail("user@test.dev").build();
        when(projectStub.getProjectMemberStatsBatch(any(GetProjectMemberStatsBatchRequest.class)))
                .thenReturn(Mono.just(GetProjectMemberStatsBatchResponse.newBuilder().addAllItems(ids.stream()
                        .map(id -> ProjectMemberStats.newBuilder().setProjectId(id).setMemberCount(7)
                                .addMembers(member).build()).toList()).build()));
        when(issueStub.countProjectIssuesBatchInternal(any(CountProjectIssuesBatchInternalRequest.class)))
                .thenReturn(Mono.just(CountProjectIssuesBatchInternalResponse.newBuilder()
                        .addItems(ProjectIssueCount.newBuilder().setProjectId(ids.getFirst()).setIssueCount(27)).build()));
        StepVerifier.create(service.getProjectStats(context)).assertNext(result -> {
            assertThat(result.getItems()).extracting(ProjectStatsDto::getProjectId).containsExactlyElementsOf(ids);
            assertThat(result.getItems().getFirst().getIssueCount()).isEqualTo(27);
            assertThat(result.getItems().get(1).getIssueCount()).isZero();
            assertThat(result.getItems().getFirst().getMemberCount()).isEqualTo(7);
            var actual = result.getItems().getFirst().getMembers().getFirst();
            assertThat(actual.getRole()).isEqualTo("VIEWER");
            assertThat(actual.getEmail()).isEqualTo("user@test.dev");
            assertThat(actual.getAvatar()).isNull();
        }).verifyComplete();
        var request = ArgumentCaptor.forClass(GetProjectMemberStatsBatchRequest.class);
        verify(projectStub).getProjectMemberStatsBatch(request.capture());
        assertThat(request.getValue().getProjectIdsList()).containsExactlyElementsOf(ids);
        assertThat(request.getValue().getActorUserId()).isEqualTo("actor");
        assertThat(request.getValue().getHeader().getRequestId()).isEqualTo("req");
        verify(issueStub).countProjectIssuesBatchInternal(any(CountProjectIssuesBatchInternalRequest.class));
        verify(projects).listMyProjects(context);
    }

    @Test
    void noProjectsSkipsAllBatches() {
        givenProjects(List.of());
        StepVerifier.create(service.getProjectStats(context))
                .assertNext(r -> assertThat(r.getItems()).isEmpty()).verifyComplete();
        verifyNoInteractions(projectStub, issueStub);
    }

    @Test
    void membershipRemovedBetweenReadsOmitsProjectAndDoesNotCountItsIssues() {
        givenProjects(List.of("removed", "kept"));
        when(projectStub.getProjectMemberStatsBatch(any(GetProjectMemberStatsBatchRequest.class)))
                .thenReturn(Mono.just(GetProjectMemberStatsBatchResponse.newBuilder().addItems(
                        ProjectMemberStats.newBuilder().setProjectId("kept").setMemberCount(1)).build()));
        when(issueStub.countProjectIssuesBatchInternal(any(CountProjectIssuesBatchInternalRequest.class)))
                .thenReturn(Mono.just(CountProjectIssuesBatchInternalResponse.getDefaultInstance()));
        StepVerifier.create(service.getProjectStats(context)).assertNext(r ->
                assertThat(r.getItems()).extracting(ProjectStatsDto::getProjectId).containsExactly("kept"))
                .verifyComplete();
        var request = ArgumentCaptor.forClass(CountProjectIssuesBatchInternalRequest.class);
        verify(issueStub).countProjectIssuesBatchInternal(request.capture());
        assertThat(request.getValue().getProjectIdsList()).containsExactly("kept");
    }

    @Test
    void batchFailurePropagatesInsteadOfReturningInventedZeroCounts() {
        givenProjects(List.of("project"));
        when(projectStub.getProjectMemberStatsBatch(any(GetProjectMemberStatsBatchRequest.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));
        StepVerifier.create(service.getProjectStats(context))
                .expectErrorMatches(e -> Status.fromThrowable(e).getCode() == Status.Code.UNAVAILABLE).verify();
        verifyNoInteractions(issueStub);
    }

    private void givenProjects(List<String> ids) {
        var response = new ListMyProjectResponseDto();
        response.setItems(ids.stream().map(id -> new ProjectResponseDto().id(id)).toList());
        when(projects.listMyProjects(context)).thenReturn(Mono.just(response));
    }
}
