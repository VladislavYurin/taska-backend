package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.CreateWorkflowStatusRequestDto;
import ru.taska.domain.dto.CreateWorkflowTransitionRequestDto;
import ru.taska.domain.dto.ProjectWorkflowDto;
import ru.taska.domain.dto.WorkflowStatusCategoryDto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class WorkflowMapperTest {

    private WorkflowMapper mapper;

    @BeforeEach
    void setUp() {
        this.mapper = new WorkflowMapper();
    }

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STATUS_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TRANSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String ISSUE_TYPE_DTO = "BUG";
    private static final Instant TIME = Instant.parse("2026-07-21T15:00:00Z");

    private static Timestamp createTimestamp() {
        return Timestamp.newBuilder()
                .setSeconds(TIME.getEpochSecond())
                .setNanos(TIME.getNano())
                .build();
    }

    @Test
    @DisplayName("Должен корректно мапить параметры и контекст в GetWorkflowForProjectRequest")
    void shouldMapToGetWorkflowGrpcRequest() {
        var userContext = GatewayUserContext.builder()
                .userId("00000000-0000-0000-0000-000000000000")
                .email("login@taska.ru")
                .displayName("Login Name")
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        var gatewayContext = new GatewayContext(
                "req-12345-abc",
                "api-gateway-node-1",
                userContext
        );

        var request = mapper.toGetWorkflowGrpcRequest(PROJECT_ID,ISSUE_TYPE_DTO,gatewayContext);

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(gatewayContext.requestId());
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(gatewayContext.nodeId());
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID.toString());
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(userContext.userId());
        Assertions.assertThat(request.getBody().getIssueType()).isEqualTo(IssueType.ISSUE_TYPE_BUG);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TASK", "BUG", "STORY"})
    @DisplayName("Должен корректно мапить все значения String в Protobuf IssueType")
    void shouldMapAllIssueTypes(String issueTypeDto) {
        var grpcType = mapper.toGrpcIssueType(issueTypeDto);

        switch (issueTypeDto) {
            case "TASK" -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_TASK);
            case "BUG" -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_BUG);
            case "STORY" -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_STORY);
        }
    }


    @Test
    @DisplayName("Должен корректно мапить gRPC WorkflowResponse в REST WorkflowResponseDto")
    void shouldMapToWorkflowResponseRestDto() {
        var grpcStatus = WorkflowStatus.newBuilder()
                .setId(STATUS_ID.toString())
                .setStatusKey("TODO")
                .setName("To Do")
                .setCategory(StatusCategory.STATUS_CATEGORY_TODO)
                .setSortOrder(10)
                .setCreatedAt(createTimestamp())
                .setUpdatedAt(createTimestamp())
                .build();

        var grpcTransition = WorkflowTransition.newBuilder()
                .setId(TRANSITION_ID.toString())
                .setFromStatusId(STATUS_ID.toString())
                .setToStatusId(UUID.randomUUID().toString())
                .setName("Start Progress")
                .setSortOrder(1)
                .setCreatedAt(createTimestamp())
                .setUpdatedAt(createTimestamp())
                .build();

        var grpcResponse = WorkflowResponse.newBuilder()
                .setId(PROJECT_ID.toString())
                .setName("Default Workflow")
                .setVersion(1)
                .setCreatedAt(createTimestamp())
                .setUpdatedAt(createTimestamp())
                .addStatuses(grpcStatus)
                .addTransitions(grpcTransition)
                .build();

        var dto = mapper.toWorkflowResponseRestDto(grpcResponse);

        Assertions.assertThat(dto.getId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(dto.getName()).isEqualTo("Default Workflow");
        Assertions.assertThat(dto.getVersion()).isEqualTo(1);
        Assertions.assertThat(dto.getCreatedAt())
                .isEqualTo(TIME.atOffset(ZoneOffset.UTC));
        Assertions.assertThat(dto.getUpdatedAt())
                .isEqualTo(TIME.atOffset(ZoneOffset.UTC));

        Assertions.assertThat(dto.getStatuses()).hasSize(1);
        var statusDto = dto.getStatuses().get(0);
        Assertions.assertThat(statusDto.getId()).isEqualTo(STATUS_ID);
        Assertions.assertThat(statusDto.getStatusKey()).isEqualTo("TODO");
        Assertions.assertThat(statusDto.getCategory()).isEqualTo("TODO");
        Assertions.assertThat(statusDto.getSortOrder()).isEqualTo(10);

        Assertions.assertThat(dto.getTransitions()).hasSize(1);
        var transitionDto = dto.getTransitions().get(0);
        Assertions.assertThat(transitionDto.getId()).isEqualTo(TRANSITION_ID);
        Assertions.assertThat(transitionDto.getName()).isEqualTo("Start Progress");
    }

    @Test
    @DisplayName("Должен очищать системные префиксы категорий статусов (STATUS_CATEGORY_*) в REST-строку")
    void shouldMapStatusCategoryCorrectly() {
        Assertions.assertThat(mapper.toRestStatusCategory(StatusCategory.STATUS_CATEGORY_TODO)).isEqualTo("TODO");
        Assertions.assertThat(mapper.toRestStatusCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)).isEqualTo("IN_PROGRESS");
        Assertions.assertThat(mapper.toRestStatusCategory(StatusCategory.STATUS_CATEGORY_DONE)).isEqualTo("DONE");
        Assertions.assertThat(mapper.toRestStatusCategory(StatusCategory.STATUS_CATEGORY_UNSPECIFIED)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Должен мапить тело создания workflow в CreateWorkflowRequest")
    void shouldMapToCreateWorkflowGrpcRequest() {
        var context = new GatewayContext("req-1", "node-1", GatewayUserContext.builder().userId("user-1").build());
        var transition = new CreateWorkflowTransitionRequestDto("TODO", "IN_PROGRESS", "Start Progress", 10);
        transition.setHidden(null);
        var request = new CreateWorkflowRequestDto(
                "Development",
                List.of("TASK", "STORY"),
                List.of(new CreateWorkflowStatusRequestDto("TODO", "To Do", WorkflowStatusCategoryDto.TODO, 10),
                        new CreateWorkflowStatusRequestDto("IN_PROGRESS", "In Progress", WorkflowStatusCategoryDto.IN_PROGRESS, 20)),
                List.of(transition));

        request.setStatusMappings(java.util.Map.of("BACKLOG", "TODO"));
        var grpc = mapper.toCreateWorkflowGrpcRequest(PROJECT_ID, request, context);

        Assertions.assertThat(grpc.getHeader().getRequestId()).isEqualTo("req-1");
        Assertions.assertThat(grpc.getBody().getProjectId()).isEqualTo(PROJECT_ID.toString());
        Assertions.assertThat(grpc.getBody().getActorUserId()).isEqualTo("user-1");
        Assertions.assertThat(grpc.getBody().getName()).isEqualTo("Development");
        Assertions.assertThat(grpc.getBody().getStatusMappingsMap()).containsEntry("BACKLOG", "TODO");
        Assertions.assertThat(grpc.getBody().getIssueTypesList())
                .containsExactly(IssueType.ISSUE_TYPE_TASK, IssueType.ISSUE_TYPE_STORY);
        Assertions.assertThat(grpc.getBody().getStatusesList()).extracting(s -> s.getCategory())
                .containsExactly(StatusCategory.STATUS_CATEGORY_TODO, StatusCategory.STATUS_CATEGORY_IN_PROGRESS);
        Assertions.assertThat(grpc.getBody().getTransitions(0).getFromStatusKey()).isEqualTo("TODO");
        Assertions.assertThat(grpc.getBody().getTransitions(0).getIsHidden()).isFalse();
    }

    @Test
    @DisplayName("Должен мапить добавляемый переход в AddWorkflowTransitionRequest")
    void shouldMapToAddWorkflowTransitionGrpcRequest() {
        var context = new GatewayContext("req-1", "node-1", GatewayUserContext.builder().userId("user-1").build());
        UUID workflowId = UUID.randomUUID();
        var transition = new CreateWorkflowTransitionRequestDto("IN_PROGRESS", "TODO", "Stop Progress", 40);
        transition.setHidden(true);

        var grpc = mapper.toAddWorkflowTransitionGrpcRequest(PROJECT_ID, workflowId, transition, context);

        Assertions.assertThat(grpc.getBody().getWorkflowId()).isEqualTo(workflowId.toString());
        Assertions.assertThat(grpc.getBody().getActorUserId()).isEqualTo("user-1");
        Assertions.assertThat(grpc.getBody().getTransition().getToStatusKey()).isEqualTo("TODO");
        Assertions.assertThat(grpc.getBody().getTransition().getSortOrder()).isEqualTo(40);
        Assertions.assertThat(grpc.getBody().getTransition().getIsHidden()).isTrue();
    }

    @Test
    @DisplayName("Должен мапить workflow проекта с типами задач, признаком shared и скрытыми переходами")
    void shouldMapListProjectWorkflowsResponse() {
        var response = ListProjectWorkflowsResponse.newBuilder()
                .addWorkflows(ProjectWorkflow.newBuilder()
                        .setWorkflow(WorkflowResponse.newBuilder()
                                .setId(UUID.randomUUID().toString())
                                .setName("Default workflow")
                                .setVersion(1)
                                .addTransitions(WorkflowTransition.newBuilder()
                                        .setId(TRANSITION_ID.toString())
                                        .setFromStatusId(STATUS_ID.toString())
                                        .setToStatusId(UUID.randomUUID().toString())
                                        .setName("Hidden")
                                        .setIsHidden(true)))
                        .addIssueTypes(IssueType.ISSUE_TYPE_BUG)
                        .addIssueTypes(IssueType.ISSUE_TYPE_UNSPECIFIED)
                        .addIssueTypeKeys("BUG").addIssueTypeKeys("REQUEST")
                        .setIsShared(true))
                .build();

        var dto = mapper.toProjectWorkflowsResponseRestDto(response);

        Assertions.assertThat(dto.getItems()).hasSize(1);
        ProjectWorkflowDto item = dto.getItems().get(0);
        Assertions.assertThat(item.getShared()).isTrue();
        Assertions.assertThat(item.getIssueTypes()).containsExactly("BUG", "REQUEST");
        Assertions.assertThat(item.getWorkflow().getName()).isEqualTo("Default workflow");
        Assertions.assertThat(item.getWorkflow().getTransitions().get(0).getHidden()).isTrue();
    }
}
