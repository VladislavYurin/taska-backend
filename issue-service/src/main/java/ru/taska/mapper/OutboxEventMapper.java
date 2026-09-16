package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.issueService.CommentCreatedPayload;
import ru.taska.event.payload.issueService.CommentDeletedPayload;
import ru.taska.event.payload.issueService.CommentUpdatedPayload;
import ru.taska.event.payload.issueService.IssueAssignedPayload;
import ru.taska.event.payload.issueService.IssueCreatedPayload;
import ru.taska.event.payload.issueService.IssueDeletedPayload;
import ru.taska.event.payload.issueService.IssueLinkCreatedPayload;
import ru.taska.event.payload.issueService.IssueLinkDeletedPayload;
import ru.taska.event.payload.issueService.IssueTransitionedPayload;
import ru.taska.event.payload.issueService.IssueUpdatedPayload;
import ru.taska.event.payload.issueService.LabelAddedPayload;
import ru.taska.event.payload.issueService.LabelRemovedPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventMapper {


    private static final String ASSIGNEE = "assigneeId";
    private static final String REPORTER = "reporterId";
    private static final String CREATED_BY = "createdBy";
    private static final String DELETED_BY = "deletedBy";
    private static final String SOURCE_ISSUE_ID = "sourceIssueId";
    private static final String TARGET_ISSUE_ID = "targetIssueId";
    private static final String LINK_TYPE = "linkType";
    private static final String SCHEMA_VERSION = "v1";

    private static final String COMMENT_ID = "commentId";
    private static final String ISSUE_ID = "issueId";
    private static final String ACTOR_USER_ID = "actorUserId";
    private static final String BODY = "body";
    private static final String OLD_BODY = "oldBody";
    private static final String NEW_BODY = "newBody";
    private static final String WATCHER_IDS = "watcherIds";
    private static final String LABEL_NAME = "labelName";


    @Value("${spring.application.name}")
    private String producerService;

    private final ObjectMapper objectMapper;

    public TaskaEvent toTaskaEvent(OutboxEvent event) {
        return TaskaEvent.builder()
                .id(event.getId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .payload(buildPayload(event))
                .requestId(event.getRequestId())
                .occurredAt(event.getCreatedAt())
                .producerService(producerService)
                .schemaVersion(SCHEMA_VERSION)
                .build();
    }

    public String toTaskaEventJsonAsString(OutboxEvent event) {
        return objectMapper.writeValueAsString(toTaskaEvent(event));
    }

    private JsonNode buildPayload(OutboxEvent event) {
        JsonNode sourcePayload = event.getPayload();
        EventType eventType = EventType.fromValue(event.getEventType());

        return switch (eventType) {
            case ISSUE_CREATED -> objectMapper.valueToTree(new IssueCreatedPayload(
                    getUuid(sourcePayload, ASSIGNEE),
                    getUuid(sourcePayload, REPORTER)
            ));

            case ISSUE_ASSIGNED -> objectMapper.valueToTree(new IssueAssignedPayload(
                    getUuid(sourcePayload, ASSIGNEE),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_TRANSITIONED -> objectMapper.valueToTree(new IssueTransitionedPayload(
                    getUuid(sourcePayload, ASSIGNEE),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_DELETED -> objectMapper.valueToTree(new IssueDeletedPayload(
                    getUuid(sourcePayload, ASSIGNEE),
                    getUuid(sourcePayload, ACTOR_USER_ID)
            ));

            case ISSUE_UPDATED -> objectMapper.valueToTree(new IssueUpdatedPayload(
                    getUuid(sourcePayload, ASSIGNEE),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_LINK_CREATED -> objectMapper.valueToTree(new IssueLinkCreatedPayload(
                    getUuid(sourcePayload, CREATED_BY),
                    getUuid(sourcePayload, SOURCE_ISSUE_ID),
                    getUuid(sourcePayload, TARGET_ISSUE_ID),
                    getString(sourcePayload, LINK_TYPE),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_LINK_DELETED -> objectMapper.valueToTree(new IssueLinkDeletedPayload(
                    getUuid(sourcePayload, DELETED_BY),
                    getUuid(sourcePayload, SOURCE_ISSUE_ID),
                    getUuid(sourcePayload, TARGET_ISSUE_ID),
                    getString(sourcePayload, LINK_TYPE),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_COMMENT_CREATED -> objectMapper.valueToTree(new CommentCreatedPayload(
                    getUuid(sourcePayload, COMMENT_ID),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    sourcePayload.path(BODY).asString(),
                    getUuidList(sourcePayload, WATCHER_IDS)
            ));

            case ISSUE_COMMENT_UPDATED -> objectMapper.valueToTree(new CommentUpdatedPayload(
                    getUuid(sourcePayload, COMMENT_ID),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    sourcePayload.path(OLD_BODY).asString(),
                    sourcePayload.path(NEW_BODY).asString()
            ));

            case ISSUE_COMMENT_DELETED -> objectMapper.valueToTree(new CommentDeletedPayload(
                    getUuid(sourcePayload, COMMENT_ID),
                    getUuid(sourcePayload, ACTOR_USER_ID),
                    sourcePayload.path(BODY).asString()
            ));

            case ISSUE_LABEL_ADDED -> objectMapper.valueToTree(new LabelAddedPayload(
                    getUuid(sourcePayload, ISSUE_ID),
                    getString(sourcePayload, LABEL_NAME),
                    getUuid(sourcePayload, CREATED_BY)
            ));

            case ISSUE_LABEL_REMOVED -> objectMapper.valueToTree(new LabelRemovedPayload(
                    getUuid(sourcePayload, ISSUE_ID),
                    getString(sourcePayload, LABEL_NAME),
                    getUuid(sourcePayload, DELETED_BY)
            ));

            default -> sourcePayload;
        };
    }

    private UUID getUuid(JsonNode node, String field) {
        if (node == null) {
            return null;
        }

        var value = node.path(field).asString();

        if (value.isEmpty()) {
            return null;
        }

        return UUID.fromString(value);
    }

    private String getString(JsonNode node, String field) {
        if (node == null) {
            return null;
        }

        var value = node.path(field).asString();

        if (value.isEmpty()) {
            return null;
        }

        return value;
    }

    private List<UUID> getUuidList(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return List.of();
        }
        JsonNode array = node.get(field);
        if (!array.isArray()) return List.of();

        List<UUID> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
                result.add(UUID.fromString(item.asString()));
        }
        return result;
    }

}