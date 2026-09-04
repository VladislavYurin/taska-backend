package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.issue.v1.IssueEventType;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.IssueLinkType;
import ru.taska.api.issue.v1.IssueLinkViewType;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueLinkTypeDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IssueMapperTest {

    private static final String ISSUE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000003";
    private static final String ASSIGNEE_ID = "00000000-0000-0000-0000-000000000004";
    private static final String HISTORY_ID = "00000000-0000-0000-0000-000000000005";
    private static final String TARGET_ISSUE_ID = "00000000-0000-0000-0000-000000000006";
    private static final String LINK_ID = "00000000-0000-0000-0000-000000000007";
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000008";
    private static final String NODE_ID = "00000000-0000-0000-0000-000000000009";
    private static final String IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000010";
    private static final GatewayUserContext USER_CONTEXT = GatewayUserContext.builder().userId(USER_ID).build();
    private static final GatewayContext CONTEXT = new GatewayContext(REQUEST_ID, NODE_ID, USER_CONTEXT);
    private static final String ISSUE_KEY = "TAS-15";
    private static final String SUMMARY = "Summary-1";
    private static final String DESCRIPTION = "Description-1";
    private static final String STATUS_KEY = "TODO";
    private static final int VERSION = 1;
    private static final int ISSUE_NUMBER = 15;
    private static final double STORY_POINTS = 5;
    private static final Integer ORIGINAL_ESTIMATE_MINUTES = 480;
    private static final Integer REMAINING_ESTIMATE_MINUTES = 240;
    private static final String START_DATE = LocalDate.of(2026, 9, 1).toString();
    private static final String DUE_DATE = LocalDate.of(2026, 9, 10).toString();
    private final IssueMapper mapper = new IssueMapper(new ObjectMapper());

    @Test
    @DisplayName("Должен корректно преобразовать IssueResponse(gRPC DTO) в IssueResponseDto(REST DTO) без Optional полей")
    void toRestIssueResponse_shouldCorrectMapsAllFields_withoutOptionalFields() {
        var createdAt = Timestamp.newBuilder()
                                 .setSeconds(1)
                                 .build();

        var updatedAt = Timestamp.newBuilder()
                                 .setSeconds(2)
                                 .build();

        var source = IssueResponse.newBuilder()
                                  .setId(ISSUE_ID)
                                  .setProjectId(PROJECT_ID)
                                  .setIssueNumber(ISSUE_NUMBER)
                                  .setIssueKey(ISSUE_KEY)
                                  .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                  .setSummary(SUMMARY)
                                  .setDescription(DESCRIPTION)
                                  .setStatusKey(STATUS_KEY)
                                  .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                  .setAssigneeId(ASSIGNEE_ID)
                                  .setReporterId(USER_ID)
                                  .setCreatedAt(createdAt)
                                  .setUpdatedAt(updatedAt)
                                  .setVersion(VERSION)
                                  .build();

        var result = mapper.toRestIssueResponse(source);

        var expectedCreatedAt = OffsetDateTime.parse("1970-01-01T00:00:01Z");
        var expectedUpdatedAt = OffsetDateTime.parse("1970-01-01T00:00:02Z");

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(PROJECT_ID, result.getProjectId());
        Assertions.assertEquals(ISSUE_NUMBER, result.getIssueNumber());
        Assertions.assertEquals(ISSUE_KEY, result.getIssueKey());
        Assertions.assertEquals("TASK", result.getIssueType());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals(DESCRIPTION, result.getDescription());
        Assertions.assertEquals(STATUS_KEY, result.getStatus());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertEquals(ASSIGNEE_ID, result.getAssigneeId());
        Assertions.assertEquals(USER_ID, result.getReporterId());
        Assertions.assertEquals(expectedCreatedAt, result.getCreatedAt());
        Assertions.assertEquals(expectedUpdatedAt, result.getUpdatedAt());
        Assertions.assertEquals(VERSION, result.getVersion());
        Assertions.assertNull(result.getStoryPoints());
        Assertions.assertNull(result.getStartDate());
        Assertions.assertNull(result.getDueDate());
        Assertions.assertNull(result.getOriginalEstimateMinutes());
        Assertions.assertNull(result.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueResponse(gRPC DTO) в IssueResponseDto(REST DTO) с Optional полями")
    void toRestIssueResponse_shouldCorrectMapsAllFields_withOptionalFields() {
        var createdAt = Timestamp.newBuilder()
                                 .setSeconds(1)
                                 .build();

        var updatedAt = Timestamp.newBuilder()
                                 .setSeconds(2)
                                 .build();

        var source = IssueResponse.newBuilder()
                                  .setId(ISSUE_ID)
                                  .setProjectId(PROJECT_ID)
                                  .setIssueNumber(ISSUE_NUMBER)
                                  .setIssueKey(ISSUE_KEY)
                                  .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                  .setSummary(SUMMARY)
                                  .setDescription(DESCRIPTION)
                                  .setStatusKey(STATUS_KEY)
                                  .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                  .setAssigneeId(ASSIGNEE_ID)
                                  .setReporterId(USER_ID)
                                  .setCreatedAt(createdAt)
                                  .setUpdatedAt(updatedAt)
                                  .setVersion(VERSION)
                                  .setStoryPoints(STORY_POINTS)
                                  .setStartDate(START_DATE)
                                  .setDueDate(DUE_DATE)
                                  .setOriginalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES)
                                  .setRemainingEstimateMinutes(REMAINING_ESTIMATE_MINUTES)
                                  .build();

        var result = mapper.toRestIssueResponse(source);

        var expectedCreatedAt = OffsetDateTime.parse("1970-01-01T00:00:01Z");
        var expectedUpdatedAt = OffsetDateTime.parse("1970-01-01T00:00:02Z");

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(PROJECT_ID, result.getProjectId());
        Assertions.assertEquals(ISSUE_NUMBER, result.getIssueNumber());
        Assertions.assertEquals(ISSUE_KEY, result.getIssueKey());
        Assertions.assertEquals("TASK", result.getIssueType());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals(DESCRIPTION, result.getDescription());
        Assertions.assertEquals(STATUS_KEY, result.getStatus());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertEquals(ASSIGNEE_ID, result.getAssigneeId());
        Assertions.assertEquals(USER_ID, result.getReporterId());
        Assertions.assertEquals(expectedCreatedAt, result.getCreatedAt());
        Assertions.assertEquals(expectedUpdatedAt, result.getUpdatedAt());
        Assertions.assertEquals(VERSION, result.getVersion());
        Assertions.assertEquals(STORY_POINTS, result.getStoryPoints());
        Assertions.assertEquals(START_DATE, result.getStartDate().toString());
        Assertions.assertEquals(DUE_DATE, result.getDueDate().toString());
        Assertions.assertEquals(ORIGINAL_ESTIMATE_MINUTES, result.getOriginalEstimateMinutes());
        Assertions.assertEquals(REMAINING_ESTIMATE_MINUTES, result.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueHistoryResponse(gRPC DTO) в IssueHistoryResponseDto(REST DTO)")
    void toRestIssueHistoryResponse_shouldCorrectMapsAllFields() {
        var occurredAt = Timestamp.newBuilder()
                                  .setSeconds(1)
                                  .build();

        var payload = """
                {
                  "oldStatus":"TODO",
                  "newStatus":"IN_PROGRESS"
                }
                """;

        var source = IssueHistoryResponse.newBuilder()
                                         .setId(HISTORY_ID)
                                         .setEventType(IssueEventType.ISSUE_EVENT_TYPE_CREATED)
                                         .setActorUserId(USER_ID)
                                         .setOccurredAt(occurredAt)
                                         .setPayload(payload)
                                         .build();

        var result = mapper.toRestIssueHistoryResponse(source);

        var expectedOccurredAt = OffsetDateTime.parse("1970-01-01T00:00:01Z");

        Assertions.assertEquals(HISTORY_ID, result.getId());
        Assertions.assertEquals("CREATED", result.getEventType());
        Assertions.assertEquals(USER_ID, result.getActorUserId());
        Assertions.assertEquals(expectedOccurredAt, result.getOccurredAt());
        Assertions.assertNotNull(result.getPayload());
        Assertions.assertEquals(
                "TODO",
                ((JsonNode) result.getPayload()).get("oldStatus").asString()
        );
        Assertions.assertEquals(
                "IN_PROGRESS",
                ((JsonNode) result.getPayload()).get("newStatus").asString()
        );
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueWithHistoryResponse(gRPC DTO) в IssueWithHistoryResponseDto(REST DTO)")
    void toRestIssueWithHistoryResponse_shouldCorrectMapsAllFields() {
        var issue = IssueResponse.newBuilder()
                                 .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                 .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                 .build();

        var historyFirst = IssueHistoryResponse.newBuilder()
                                               .setEventType(IssueEventType.ISSUE_EVENT_TYPE_CREATED)
                                               .build();

        var historySecond = IssueHistoryResponse.newBuilder()
                                                .setEventType(IssueEventType.ISSUE_EVENT_TYPE_TRANSITIONED)
                                                .build();

        var source = IssueWithHistoryResponse.newBuilder()
                                             .setIssue(issue)
                                             .addAllHistory(List.of(historyFirst, historySecond))
                                             .build();

        var result = mapper.toRestIssueWithHistoryResponse(source);

        Assertions.assertNotNull(result.getIssue());
        Assertions.assertEquals("TASK", result.getIssue().getIssueType());
        Assertions.assertEquals("MEDIUM", result.getIssue().getPriority());
        Assertions.assertNotNull(result.getHistory());
        Assertions.assertEquals(2, result.getHistory().size());
        Assertions.assertEquals("CREATED", result.getHistory().getFirst().getEventType());
        Assertions.assertEquals("TRANSITIONED", result.getHistory().get(1).getEventType());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueShortResponse(gRPC DTO) в IssueShortResponseDto(REST DTO) без Optional полей")
    void toIssueShortResponseDto_shouldCorrectMapsAllFields_withoutOptionalFields() {
        var shortIssue = IssueShortResponse.newBuilder()
                                           .setId(ISSUE_ID)
                                           .setIssueKey(ISSUE_KEY)
                                           .setSummary(SUMMARY)
                                           .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                           .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                           .setAssigneeId(ASSIGNEE_ID)
                                           .build();

        var result = mapper.toIssueShortResponseDto(shortIssue);

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(ISSUE_KEY, result.getIssueKey());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals("TASK", result.getIssueType());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertEquals(ASSIGNEE_ID, result.getAssigneeId());
        Assertions.assertNull(result.getStoryPoints());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueShortResponse(gRPC DTO) в IssueShortResponseDto(REST DTO) с Optional полями")
    void toIssueShortResponseDto_shouldCorrectMapsAllFields_withOptionalFields() {
        var shortIssue = IssueShortResponse.newBuilder()
                                           .setId(ISSUE_ID)
                                           .setIssueKey(ISSUE_KEY)
                                           .setSummary(SUMMARY)
                                           .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                           .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                           .setAssigneeId(ASSIGNEE_ID)
                                           .setStoryPoints(STORY_POINTS)
                                           .build();

        var result = mapper.toIssueShortResponseDto(shortIssue);

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(ISSUE_KEY, result.getIssueKey());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals("TASK", result.getIssueType());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertEquals(ASSIGNEE_ID, result.getAssigneeId());
        Assertions.assertEquals(STORY_POINTS, result.getStoryPoints());
    }

    @Test
    @DisplayName("Должен корректно преобразовать ListIssuesResponse(gRPC DTO) в ListIssuesResponseDto(REST DTO)")
    void toRestListIssuesResponseDto_shouldCorrectMapsAllFields() {
        var firstIssue = IssueResponse.newBuilder()
                                      .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                      .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                      .build();

        var secondIssue = IssueResponse.newBuilder()
                                       .setIssueType(IssueType.ISSUE_TYPE_BUG)
                                       .setPriority(IssuePriority.ISSUE_PRIORITY_HIGH)
                                       .build();

        var source = ListIssuesResponse.newBuilder()
                                       .addAllIssues(List.of(firstIssue, secondIssue))
                                       .setTotalCount(5)
                                       .build();

        var result = mapper.toRestListIssuesResponseDto(source);

        Assertions.assertNotNull(result.getItems());
        Assertions.assertEquals(2, result.getItems().size());
        Assertions.assertEquals("TASK", result.getItems().getFirst().getIssueType());
        Assertions.assertEquals("MEDIUM", result.getItems().getFirst().getPriority());
        Assertions.assertEquals("BUG", result.getItems().get(1).getIssueType());
        Assertions.assertEquals("HIGH", result.getItems().get(1).getPriority());
        Assertions.assertEquals(5, result.getTotalCount());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueResponse с метками в IssueResponseDto")
    void toRestIssueResponse_shouldCorrectMapLabels() {
        var label1 = ProjectLabelResponse.newBuilder()
                                         .setId("00000000-0000-0000-0000-000000000010")
                                         .setName("test")
                                         .setColor("#8B5CF6")
                                         .build();

        var label2 = ProjectLabelResponse.newBuilder()
                                         .setId("00000000-0000-0000-0000-000000000011")
                                         .setName("test2")
                                         .setColor("#E3A008")
                                         .build();

        var source = IssueResponse.newBuilder()
                                  .setId(ISSUE_ID)
                                  .setProjectId(PROJECT_ID)
                                  .setIssueNumber(ISSUE_NUMBER)
                                  .setIssueKey(ISSUE_KEY)
                                  .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                  .setSummary(SUMMARY)
                                  .setDescription(DESCRIPTION)
                                  .setStatusKey(STATUS_KEY)
                                  .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                  .setAssigneeId(ASSIGNEE_ID)
                                  .setReporterId(USER_ID)
                                  .setVersion(VERSION)
                                  .addAllLabels(List.of(label1, label2))
                                  .build();

        var result = mapper.toRestIssueResponse(source);

        Assertions.assertNotNull(result.getLabels());
        Assertions.assertEquals(2, result.getLabels().size());

        var resultLabel1 = result.getLabels().get(0);
        Assertions.assertEquals(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                resultLabel1.getId()
        );
        Assertions.assertEquals("test", resultLabel1.getName());
        Assertions.assertEquals("#8B5CF6", resultLabel1.getColor());

        var resultLabel2 = result.getLabels().get(1);
        Assertions.assertEquals(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                resultLabel2.getId()
        );
        Assertions.assertEquals("test2", resultLabel2.getName());
        Assertions.assertEquals("#E3A008", resultLabel2.getColor());
    }

    @Test
    @DisplayName("Должен корректно преобразовать ListIssuesResponse с метками в ListIssuesResponseDto")
    void toRestListIssuesResponseDto_shouldCorrectMapLabels() {
        var label1 = ProjectLabelResponse.newBuilder()
                                         .setId("00000000-0000-0000-0000-000000000010")
                                         .setName("test")
                                         .setColor("#8B5CF6")
                                         .build();

        var label2 = ProjectLabelResponse.newBuilder()
                                         .setId("00000000-0000-0000-0000-000000000011")
                                         .setName("test2")
                                         .setColor("#E3A008")
                                         .build();

        var firstIssue = IssueResponse.newBuilder()
                                      .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                      .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                      .addAllLabels(List.of(label1, label2))
                                      .build();

        var secondIssue = IssueResponse.newBuilder()
                                       .setIssueType(IssueType.ISSUE_TYPE_BUG)
                                       .setPriority(IssuePriority.ISSUE_PRIORITY_HIGH)
                                       .build();

        var source = ListIssuesResponse.newBuilder()
                                       .addAllIssues(List.of(firstIssue, secondIssue))
                                       .setTotalCount(5)
                                       .build();

        var result = mapper.toRestListIssuesResponseDto(source);

        Assertions.assertNotNull(result.getItems());
        Assertions.assertEquals(2, result.getItems().size());

        var firstResult = result.getItems().get(0);
        Assertions.assertEquals("TASK", firstResult.getIssueType());
        Assertions.assertEquals("MEDIUM", firstResult.getPriority());
        Assertions.assertNotNull(firstResult.getLabels());
        Assertions.assertEquals(2, firstResult.getLabels().size());
        Assertions.assertEquals("test", firstResult.getLabels().get(0).getName());
        Assertions.assertEquals("#8B5CF6", firstResult.getLabels().get(0).getColor());
        Assertions.assertEquals("test2", firstResult.getLabels().get(1).getName());
        Assertions.assertEquals("#E3A008", firstResult.getLabels().get(1).getColor());

        var secondResult = result.getItems().get(1);
        Assertions.assertEquals("BUG", secondResult.getIssueType());
        Assertions.assertEquals("HIGH", secondResult.getPriority());
        Assertions.assertNotNull(secondResult.getLabels());
        Assertions.assertTrue(secondResult.getLabels().isEmpty());

        Assertions.assertEquals(5, result.getTotalCount());
    }

    @Test
    @DisplayName("Должен корректно преобразовать UpdateIssueResponse(gRPC DTO) в UpdateIssueResponseDto(REST DTO) без Optional полей")
    void toRestUpdateResponse_shouldCorrectMapsAllFields_withoutOptionalFields() {
        var source = UpdateIssueResponse.newBuilder()
                                        .setUpdatedIssueId(ISSUE_ID)
                                        .setSummary(SUMMARY)
                                        .setDescription(DESCRIPTION)
                                        .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                        .build();

        var result = mapper.toRestUpdateResponse(source);

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals(DESCRIPTION, result.getDescription());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertNull(result.getStoryPoints());
        Assertions.assertNull(result.getStartDate());
        Assertions.assertNull(result.getDueDate());
        Assertions.assertNull(result.getOriginalEstimateMinutes());
        Assertions.assertNull(result.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать UpdateIssueResponse(gRPC DTO) в UpdateIssueResponseDto(REST DTO)")
    void toRestUpdateResponse_shouldCorrectMapsAllFields() {
        var source = UpdateIssueResponse.newBuilder()
                                        .setUpdatedIssueId(ISSUE_ID)
                                        .setSummary(SUMMARY)
                                        .setDescription(DESCRIPTION)
                                        .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                        .setStoryPoints(STORY_POINTS)
                                        .setStartDate(START_DATE)
                                        .setDueDate(DUE_DATE)
                                        .setOriginalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES)
                                        .setRemainingEstimateMinutes(REMAINING_ESTIMATE_MINUTES)
                                        .build();

        var result = mapper.toRestUpdateResponse(source);

        Assertions.assertEquals(ISSUE_ID, result.getId());
        Assertions.assertEquals(SUMMARY, result.getSummary());
        Assertions.assertEquals(DESCRIPTION, result.getDescription());
        Assertions.assertEquals("MEDIUM", result.getPriority());
        Assertions.assertEquals(STORY_POINTS, result.getStoryPoints());
        Assertions.assertEquals(START_DATE, result.getStartDate().toString());
        Assertions.assertEquals(DUE_DATE, result.getDueDate().toString());
        Assertions.assertEquals(ORIGINAL_ESTIMATE_MINUTES, result.getOriginalEstimateMinutes());
        Assertions.assertEquals(REMAINING_ESTIMATE_MINUTES, result.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать IssueLinkResponse(gRPC DTO) в IssueLinkResponseDto(REST DTO)")
    void toRestIssueLinkResponse_shouldCorrectMapsAllFields() {
        var createdAt = Timestamp.newBuilder()
                                 .setSeconds(1)
                                 .build();

        var expectedCreatedAt = OffsetDateTime.parse("1970-01-01T00:00:01Z");

        var source = IssueLinkResponse.newBuilder()
                                      .setId(LINK_ID)
                                      .setProjectId(PROJECT_ID)
                                      .setSourceIssueId(ISSUE_ID)
                                      .setTargetIssueId(TARGET_ISSUE_ID)
                                      .setViewLinkType(IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_BLOCKS)
                                      .setCreatedBy(USER_ID)
                                      .setCreatedAt(createdAt)
                                      .build();

        var result = mapper.toRestIssueLinkResponse(source);

        Assertions.assertEquals(LINK_ID, result.getId());
        Assertions.assertEquals(PROJECT_ID, result.getProjectId());
        Assertions.assertEquals(ISSUE_ID, result.getSourceIssueId());
        Assertions.assertEquals(TARGET_ISSUE_ID, result.getTargetIssueId());
        Assertions.assertEquals("ISSUE_LINK_VIEW_TYPE_BLOCKS", result.getViewLinkType());
        Assertions.assertEquals(USER_ID, result.getCreatedBy());
        Assertions.assertEquals(expectedCreatedAt, result.getCreatedAt());
    }

    @Test
    @DisplayName("Должен корректно преобразовать ListIssueLinksResponse(gRPC DTO) в ListIssueLinksResponseDto(REST DTO)")
    void toRestListIssueLinkResponse_shouldCorrectMapsAllFields() {
        var firstLink = IssueLinkResponse.newBuilder()
                                         .setId(LINK_ID)
                                         .build();

        var secondLink = IssueLinkResponse.newBuilder()
                                          .setId("00000000-0000-0000-0000-000000000008")
                                          .build();

        var source = ListIssueLinksResponse.newBuilder()
                                           .addAllIssueLinks(List.of(firstLink, secondLink))
                                           .build();

        var result = mapper.toRestListIssueLinkResponse(source);

        Assertions.assertEquals(2, result.getItems().size());
        Assertions.assertEquals(LINK_ID, result.getItems().getFirst().getId());
        Assertions.assertEquals(
                "00000000-0000-0000-0000-000000000008",
                result.getItems().get(1).getId()
        );
    }


    @Test
    @DisplayName("Должен корректно преобразовать CreateIssueRequestDto(REST DTO) в CreateIssueRequest(gRPC DTO) без Optional полей")
    void toCreateIssueGrpcRequest_shouldCorrectMapsAllFields_withoutOptionalFields() {
        var source = new CreateIssueRequestDto("TASK", SUMMARY, DESCRIPTION, "LOW");

        var result = mapper.toCreateIssueGrpcRequest(PROJECT_ID, IDEMPOTENCY_KEY, source, CONTEXT);
        var resultBody = result.getBody();

        Assertions.assertEquals(REQUEST_ID, result.getHeader().getRequestId());
        Assertions.assertEquals(NODE_ID, result.getHeader().getNodeId());
        Assertions.assertEquals(IDEMPOTENCY_KEY, resultBody.getIdempotencyKey());
        Assertions.assertEquals(PROJECT_ID, resultBody.getProjectId());
        Assertions.assertEquals(IssueType.ISSUE_TYPE_TASK, resultBody.getIssueType());
        Assertions.assertEquals(SUMMARY, resultBody.getSummary());
        Assertions.assertEquals(DESCRIPTION, resultBody.getDescription());
        Assertions.assertEquals(IssuePriority.ISSUE_PRIORITY_LOW, resultBody.getPriority());
        Assertions.assertEquals(USER_ID, resultBody.getReporterId());
        Assertions.assertFalse(resultBody.hasStoryPoints());
        Assertions.assertFalse(resultBody.hasStartDate());
        Assertions.assertFalse(resultBody.hasDueDate());
        Assertions.assertFalse(resultBody.hasOriginalEstimateMinutes());
        Assertions.assertFalse(resultBody.hasRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать CreateIssueRequestDto(REST DTO) в CreateIssueRequest(gRPC DTO) c Optional полями")
    void toCreateIssueGrpcRequest_shouldCorrectMapsAllFields_withOptionalFields() {
        var source = new CreateIssueRequestDto("TASK", SUMMARY, DESCRIPTION, "LOW");
        source.setStoryPoints(STORY_POINTS);
        source.setStartDate(LocalDate.parse(START_DATE));
        source.setDueDate(LocalDate.parse(DUE_DATE));
        source.setOriginalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES);
        source.setRemainingEstimateMinutes(REMAINING_ESTIMATE_MINUTES);

        var result = mapper.toCreateIssueGrpcRequest(PROJECT_ID, IDEMPOTENCY_KEY, source, CONTEXT);
        var resultBody = result.getBody();

        Assertions.assertEquals(REQUEST_ID, result.getHeader().getRequestId());
        Assertions.assertEquals(NODE_ID, result.getHeader().getNodeId());
        Assertions.assertEquals(IDEMPOTENCY_KEY, resultBody.getIdempotencyKey());
        Assertions.assertEquals(PROJECT_ID, resultBody.getProjectId());
        Assertions.assertEquals(IssueType.ISSUE_TYPE_TASK, resultBody.getIssueType());
        Assertions.assertEquals(SUMMARY, resultBody.getSummary());
        Assertions.assertEquals(DESCRIPTION, resultBody.getDescription());
        Assertions.assertEquals(IssuePriority.ISSUE_PRIORITY_LOW, resultBody.getPriority());
        Assertions.assertEquals(USER_ID, resultBody.getReporterId());
        Assertions.assertEquals(STORY_POINTS, resultBody.getStoryPoints());
        Assertions.assertEquals(START_DATE, resultBody.getStartDate());
        Assertions.assertEquals(DUE_DATE, resultBody.getDueDate());
        Assertions.assertEquals(ORIGINAL_ESTIMATE_MINUTES, resultBody.getOriginalEstimateMinutes());
        Assertions.assertEquals(REMAINING_ESTIMATE_MINUTES, resultBody.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать CreateIssueRequestDto(REST DTO) в CreateIssueRequest(gRPC DTO) без Optional полей")
    void toUpdateIssueRequest_shouldCorrectMapsAllFields_withoutOptionalFields() {
        var source = new UpdateIssueRequestDto(SUMMARY, DESCRIPTION, "LOW");

        var result = mapper.toUpdateIssueRequest(ISSUE_ID, source, CONTEXT);
        var resultBody = result.getBody();

        Assertions.assertEquals(REQUEST_ID, result.getHeader().getRequestId());
        Assertions.assertEquals(NODE_ID, result.getHeader().getNodeId());
        Assertions.assertEquals(ISSUE_ID, resultBody.getIssueId());
        Assertions.assertEquals(USER_ID, resultBody.getActorUserId());
        Assertions.assertEquals(SUMMARY, resultBody.getSummary());
        Assertions.assertEquals(DESCRIPTION, resultBody.getDescription());
        Assertions.assertEquals(IssuePriority.ISSUE_PRIORITY_LOW, resultBody.getPriority());
        Assertions.assertFalse(resultBody.hasStoryPoints());
        Assertions.assertFalse(resultBody.hasStartDate());
        Assertions.assertFalse(resultBody.hasDueDate());
        Assertions.assertFalse(resultBody.hasOriginalEstimateMinutes());
        Assertions.assertFalse(resultBody.hasRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовать CreateIssueRequestDto(REST DTO) в CreateIssueRequest(gRPC DTO) c Optional полями")
    void toUpdateIssueRequest_shouldCorrectMapsAllFields_withOptionalFields() {
        var source = new UpdateIssueRequestDto(SUMMARY, DESCRIPTION, "LOW");
        source.setStoryPoints(STORY_POINTS);
        source.setStartDate(LocalDate.parse(START_DATE));
        source.setDueDate(LocalDate.parse(DUE_DATE));
        source.setOriginalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES);
        source.setRemainingEstimateMinutes(REMAINING_ESTIMATE_MINUTES);

        var result = mapper.toUpdateIssueRequest(ISSUE_ID, source, CONTEXT);
        var resultBody = result.getBody();

        Assertions.assertEquals(REQUEST_ID, result.getHeader().getRequestId());
        Assertions.assertEquals(NODE_ID, result.getHeader().getNodeId());
        Assertions.assertEquals(ISSUE_ID, resultBody.getIssueId());
        Assertions.assertEquals(USER_ID, resultBody.getActorUserId());
        Assertions.assertEquals(SUMMARY, resultBody.getSummary());
        Assertions.assertEquals(DESCRIPTION, resultBody.getDescription());
        Assertions.assertEquals(IssuePriority.ISSUE_PRIORITY_LOW, resultBody.getPriority());
        Assertions.assertEquals(STORY_POINTS, resultBody.getStoryPoints());
        Assertions.assertEquals(START_DATE, resultBody.getStartDate());
        Assertions.assertEquals(DUE_DATE, resultBody.getDueDate());
        Assertions.assertEquals(ORIGINAL_ESTIMATE_MINUTES, resultBody.getOriginalEstimateMinutes());
        Assertions.assertEquals(REMAINING_ESTIMATE_MINUTES, resultBody.getRemainingEstimateMinutes());
    }

    @Test
    @DisplayName("Должен корректно преобразовывать Timestamp(protobuf) в OffsetDataTime")
    void toOffsetDateTime_shouldCorrectConvertTimestamp() {
        var source = Timestamp.newBuilder()
                              .setSeconds(1)
                              .build();

        var result = mapper.toOffsetDateTime(source);

        Assertions.assertEquals(OffsetDateTime.parse("1970-01-01T00:00:01Z"), result);
    }

    @Test
    @DisplayName("Должен корректно десериализовать payload")
    void parsePayload_shouldCorrectMapsToJsonNode() {
        var payload = """
                {
                  "oldStatus":"TODO",
                  "newStatus":"IN_PROGRESS"
                }
                """;

        var result = mapper.parsePayload(payload);

        Assertions.assertNotNull(result);
        Assertions.assertInstanceOf(JsonNode.class, result);

        var json = (JsonNode) result;

        Assertions.assertEquals("TODO", json.get("oldStatus").asString());
        Assertions.assertEquals("IN_PROGRESS", json.get("newStatus").asString());
    }

    @Test
    @DisplayName("Должен вернуть пустой JsonNode, если payload отсутствует")
    void parsePayload_shouldReturnEmptyObject_whenPayloadIsEmpty() {
        var result = mapper.parsePayload("");

        Assertions.assertInstanceOf(JsonNode.class, result);

        var json = (JsonNode) result;

        Assertions.assertTrue(json.isObject());
        Assertions.assertTrue(json.isEmpty());
    }

    @Test
    @DisplayName("Должен вернуть пустой JsonNode, если payload равен null")
    void parsePayload_shouldReturnEmptyObject_whenPayloadIsNull() {
        var result = mapper.parsePayload(null);

        Assertions.assertInstanceOf(JsonNode.class, result);

        var json = (JsonNode) result;

        Assertions.assertTrue(json.isObject());
        Assertions.assertTrue(json.isEmpty());
    }

    @Test
    @DisplayName("Дролжен выбросить исключение IllegalArgumentException при невалидном payload")
    void parsePayload_shouldThrowsException_whenPayloadIsInvalid() {
        var invalidPayload = """
                {
                    oldstatus
                }
                """;
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> mapper.parsePayload(invalidPayload)
        );
    }

    @Test
    @DisplayName("Должен корректно сериализовать payload в строку")
    void convertPayloadToJsonString_shouldCorrectSerializeToString() {
        var payload = new ObjectMapper().createObjectNode()
                                        .put("oldStatus", "TODO")
                                        .put("newStatus", "IN_PROGRESS");

        var result = mapper.convertPayloadToJsonString(payload);

        Assertions.assertTrue(result.contains("\"oldStatus\":\"TODO\""));
        Assertions.assertTrue(result.contains("\"newStatus\":\"IN_PROGRESS\""));
    }

    @ParameterizedTest
    @MethodSource("grpcIssueTypeArguments")
    @DisplayName("Должен корректно преобразовать REST issueType в gRPC issueType")
    void toGrpcIssueType_shouldCorrectMapsAllFields(String source, IssueType expected) {
        var result = mapper.toGrpcIssueType(source);

        Assertions.assertEquals(expected, result);
    }

    @ParameterizedTest
    @MethodSource("restIssueTypeArguments")
    @DisplayName("Должен корректно преобразовать gRPC issueType в REST issueType")
    void toRestIssueType_shouldCorrectMapsAllFields(IssueType source, String expected) {
        var result = mapper.toRestIssueType(source);

        Assertions.assertEquals(expected, result);
    }

    @Test
    @DisplayName("Должен выбрасывать ResponseStatusException, если приходит неизвестный gRPC issueType")
    void toRestIssueType_shouldThrowsException_whenUnknownIssueType() {
        var ex = Assertions.assertThrows(
                ResponseStatusException.class,
                () -> mapper.toRestIssueType(IssueType.ISSUE_TYPE_UNSPECIFIED)
        );

        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
    }

    @ParameterizedTest
    @MethodSource("grpcIssuePriorityArguments")
    @DisplayName("Должен корректно преобразовать REST issuePriority в gRPC issuePriority")
    void toGrpcIssuePriority_shouldCorrectMapsAllFields(String source, IssuePriority expected) {
        var result = mapper.toGrpcIssuePriority(source);

        Assertions.assertEquals(expected, result);
    }

    @ParameterizedTest
    @MethodSource("restIssuePriorityArguments")
    @DisplayName("Должен корректно преобразовать gRPC issueType в REST issueType")
    void toRestIssuePriority_shouldCorrectMapsAllFields(IssuePriority source, String expected) {
        var result = mapper.toRestIssuePriority(source);

        Assertions.assertEquals(expected, result);
    }

    @Test
    @DisplayName("Должен выбрасывать ResponseStatusException, если приходит неизвестный gRPC issuePriority")
    void toRestIssuePriority_shouldThrowsException_whenUnknownIssuePriority() {
        var ex = Assertions.assertThrows(
                ResponseStatusException.class,
                () -> mapper.toRestIssuePriority(IssuePriority.ISSUE_PRIORITY_UNSPECIFIED)
        );

        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
    }

    @ParameterizedTest
    @MethodSource("restIssueEventTypeArguments")
    @DisplayName("Должен корректно преобразовать gRPC issueEventType в REST issueEventType")
    void toRestIssueEventType_shouldCorrectMapsAllFields(IssueEventType source, String expected) {
        var result = mapper.toRestIssueEventType(source);

        Assertions.assertEquals(expected, result);
    }

    @Test
    @DisplayName("Должен возвращать строку для неизвестного gRPC issueEventType")
    void toRestIssueEventType_shouldThrowsException_whenUnknownIssuePriority() {
        var result = mapper.toRestIssueEventType(IssueEventType.ISSUE_EVENT_TYPE_UNSPECIFIED);
        Assertions.assertEquals("UNSPECIFIED", result);

    }

    @ParameterizedTest
    @MethodSource("grpcIssueLinkTypeArguments")
    @DisplayName("Должен корректно преобразовать REST IssueLinkTypeDto в gRPC IssueLinkType")
    void toGrpcIssueLinkType_(IssueLinkTypeDto source, IssueLinkType expected) {
        var result = mapper.toGrpcIssueLinkType(source);

        Assertions.assertEquals(expected, result);
    }

    private static Stream<Arguments> grpcIssueTypeArguments() {
        return Stream.of(
                Arguments.of("TASK", IssueType.ISSUE_TYPE_TASK),
                Arguments.of("BUG", IssueType.ISSUE_TYPE_BUG),
                Arguments.of("STORY", IssueType.ISSUE_TYPE_STORY),
                Arguments.of("UNKNOWN", IssueType.ISSUE_TYPE_UNSPECIFIED)
        );
    }

    private static Stream<Arguments> restIssueTypeArguments() {
        return Stream.of(
                Arguments.of(IssueType.ISSUE_TYPE_TASK, "TASK"),
                Arguments.of(IssueType.ISSUE_TYPE_BUG, "BUG"),
                Arguments.of(IssueType.ISSUE_TYPE_STORY, "STORY")
        );
    }

    private static Stream<Arguments> grpcIssuePriorityArguments() {
        return Stream.of(
                Arguments.of("LOW", IssuePriority.ISSUE_PRIORITY_LOW),
                Arguments.of("MEDIUM", IssuePriority.ISSUE_PRIORITY_MEDIUM),
                Arguments.of("HIGH", IssuePriority.ISSUE_PRIORITY_HIGH),
                Arguments.of("UNSPECIFIED", IssuePriority.ISSUE_PRIORITY_UNSPECIFIED)
        );
    }

    private static Stream<Arguments> restIssuePriorityArguments() {
        return Stream.of(
                Arguments.of(IssuePriority.ISSUE_PRIORITY_LOW, "LOW"),
                Arguments.of(IssuePriority.ISSUE_PRIORITY_MEDIUM, "MEDIUM"),
                Arguments.of(IssuePriority.ISSUE_PRIORITY_HIGH, "HIGH")
        );
    }

    private static Stream<Arguments> restIssueEventTypeArguments() {
        return Stream.of(
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_CREATED, "CREATED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_UPDATED, "UPDATED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_ASSIGNED, "ASSIGNED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_TRANSITIONED, "TRANSITIONED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_DELETED, "DELETED"),
                Arguments.of(
                        IssueEventType.ISSUE_EVENT_TYPE_ATTACHMENT_UPLOADED,
                        "ATTACHMENT_UPLOADED"
                ),
                Arguments.of(
                        IssueEventType.ISSUE_EVENT_TYPE_ATTACHMENT_DELETED,
                        "ATTACHMENT_DELETED"
                ),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_COMMENT_CREATED, "COMMENT_CREATED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_COMMENT_UPDATED, "COMMENT_UPDATED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_COMMENT_DELETED, "COMMENT_DELETED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_LABEL_ADDED, "LABEL_ADDED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_LABEL_REMOVED, "LABEL_REMOVED"),
                Arguments.of(IssueEventType.ISSUE_EVENT_TYPE_UNSPECIFIED, "UNSPECIFIED")
        );
    }

    private static Stream<Arguments> grpcIssueLinkTypeArguments() {
        return Stream.of(
                Arguments.of(IssueLinkTypeDto.RELATES_TO, IssueLinkType.ISSUE_LINK_TYPE_RELATES_TO),
                Arguments.of(IssueLinkTypeDto.BLOCKS, IssueLinkType.ISSUE_LINK_TYPE_BLOCKS),
                Arguments.of(IssueLinkTypeDto.DUPLICATES, IssueLinkType.ISSUE_LINK_TYPE_DUPLICATES)
        );
    }

}