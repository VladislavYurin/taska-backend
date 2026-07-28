package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.IssueTypeDto;

import java.time.OffsetDateTime;
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
    private static final IssueTypeDto ISSUE_TYPE_DTO = IssueTypeDto.BUG;
    private static final String ISO_DATE = "2026-07-21T18:00:00+03:00";

    @Test
    @DisplayName("Должен корректно мапить параметры и контекст в GetWorkflowForProjectRequest")
    void shouldMapToGetWorkflowGrpcRequest() {
        var userContext = GatewayUserContext.builder()
                .userId("00000000-0000-0000-0000-000000000000")
                .login("Login")
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
    @EnumSource(IssueTypeDto.class)
    @DisplayName("Должен корректно мапить все значения IssueTypeDto в Protobuf IssueType")
    void shouldMapAllIssueTypes(IssueTypeDto issueTypeDto) {
        var grpcType = mapper.toGrpcIssueType(issueTypeDto);

        switch (issueTypeDto) {
            case TASK -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_TASK);
            case BUG -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_BUG);
            case STORY -> Assertions.assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_STORY);
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
                .setCreatedAt(ISO_DATE)
                .setUpdatedAt(ISO_DATE)
                .build();

        var grpcTransition = WorkflowTransition.newBuilder()
                .setId(TRANSITION_ID.toString())
                .setFromStatusId(STATUS_ID.toString())
                .setToStatusId(UUID.randomUUID().toString())
                .setName("Start Progress")
                .setSortOrder(1)
                .setCreatedAt(ISO_DATE)
                .setUpdatedAt(ISO_DATE)
                .build();

        var grpcResponse = WorkflowResponse.newBuilder()
                .setId(PROJECT_ID.toString())
                .setName("Default Workflow")
                .setVersion(1)
                .setCreatedAt(ISO_DATE)
                .setUpdatedAt(ISO_DATE)
                .addStatuses(grpcStatus)
                .addTransitions(grpcTransition)
                .build();

        var dto = mapper.toWorkflowResponseRestDto(grpcResponse);

        Assertions.assertThat(dto.getId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(dto.getName()).isEqualTo("Default Workflow");
        Assertions.assertThat(dto.getVersion()).isEqualTo(1);
        Assertions.assertThat(dto.getCreatedAt()).isEqualTo(OffsetDateTime.parse(ISO_DATE));
        Assertions.assertThat(dto.getUpdatedAt()).isEqualTo(OffsetDateTime.parse(ISO_DATE));

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

}