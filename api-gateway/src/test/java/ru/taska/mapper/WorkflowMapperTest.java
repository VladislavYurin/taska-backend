package ru.taska.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.taska.api.workflow.v1.*;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.IssueTypeDto;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(request.getHeader().getRequestId()).isEqualTo(gatewayContext.requestId());
        assertThat(request.getHeader().getNodeId()).isEqualTo(gatewayContext.nodeId());
        assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID.toString());
        assertThat(request.getBody().getActorUserId()).isEqualTo(userContext.userId());
        assertThat(request.getBody().getIssueType()).isEqualTo(IssueType.ISSUE_TYPE_BUG);
    }

    @ParameterizedTest
    @EnumSource(IssueTypeDto.class)
    @DisplayName("Должен корректно мапить все значения IssueTypeDto в Protobuf IssueType")
    void shouldMapAllIssueTypes(IssueTypeDto issueTypeDto) {
        var grpcType = mapper.toIssueType(issueTypeDto);

        switch (issueTypeDto) {
            case TASK -> assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_TASK);
            case BUG -> assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_BUG);
            case STORY -> assertThat(grpcType).isEqualTo(IssueType.ISSUE_TYPE_STORY);
        }
    }


    @Test
    @DisplayName("Должен корректно мапить gRPC WorkflowResponse в REST WorkflowResponseDto")
    void shouldMapToWorkflowResponseDto() {
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

        var dto = mapper.toWorkflowResponseDto(grpcResponse);

        assertThat(dto.getId()).isEqualTo(PROJECT_ID);
        assertThat(dto.getName()).isEqualTo("Default Workflow");
        assertThat(dto.getVersion()).isEqualTo(1);
        assertThat(dto.getCreatedAt()).isEqualTo(OffsetDateTime.parse(ISO_DATE));
        assertThat(dto.getUpdatedAt()).isEqualTo(OffsetDateTime.parse(ISO_DATE));

        assertThat(dto.getStatuses()).hasSize(1);
        var statusDto = dto.getStatuses().get(0);
        assertThat(statusDto.getId()).isEqualTo(STATUS_ID);
        assertThat(statusDto.getStatusKey()).isEqualTo("TODO");
        assertThat(statusDto.getCategory()).isEqualTo("TODO");
        assertThat(statusDto.getSortOrder()).isEqualTo(10);

        assertThat(dto.getTransitions()).hasSize(1);
        var transitionDto = dto.getTransitions().get(0);
        assertThat(transitionDto.getId()).isEqualTo(TRANSITION_ID);
        assertThat(transitionDto.getName()).isEqualTo("Start Progress");
    }

    @Test
    @DisplayName("Должен очищать системные префиксы категорий статусов (STATUS_CATEGORY_*) в REST-строку")
    void shouldMapStatusCategoryCorrectly() {
        assertThat(mapper.toStatusCategory(StatusCategory.STATUS_CATEGORY_TODO)).isEqualTo("TODO");
        assertThat(mapper.toStatusCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)).isEqualTo("IN_PROGRESS");
        assertThat(mapper.toStatusCategory(StatusCategory.STATUS_CATEGORY_DONE)).isEqualTo("DONE");
        assertThat(mapper.toStatusCategory(StatusCategory.STATUS_CATEGORY_UNSPECIFIED)).isEqualTo("UNKNOWN");
    }

}