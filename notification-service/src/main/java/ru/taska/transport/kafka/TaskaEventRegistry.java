package ru.taska.transport.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.event.EventType;
import ru.taska.event.EventScopeKind;
import ru.taska.event.TaskaEvent;
import ru.taska.event.TaskaEventContract;

import java.util.EnumSet;
import java.util.Set;

/** Validates the trusted producer/topic/scope part of an incoming event contract. */
@Component
final class TaskaEventRegistry {

    private final String issueTopic;
    private final String projectTopic;
    private final String userTopic;

    private static final Set<EventType> ISSUE_EVENTS = Set.of(
            EventType.ISSUE_CREATED,
            EventType.ISSUE_ASSIGNED,
            EventType.ISSUE_TRANSITIONED,
            EventType.ISSUE_UPDATED,
            EventType.ISSUE_DELETED,
            EventType.ISSUE_LINK_CREATED,
            EventType.ISSUE_LINK_DELETED,
            EventType.ISSUE_ATTACHMENT_ADDED,
            EventType.ISSUE_ATTACHMENT_DELETED,
            EventType.ISSUE_COMMENT_CREATED,
            EventType.ISSUE_COMMENT_UPDATED,
            EventType.ISSUE_COMMENT_DELETED,
            EventType.ISSUE_LABEL_ADDED,
            EventType.ISSUE_LABEL_REMOVED,
            EventType.ISSUE_WATCHED,
            EventType.ISSUE_UNWATCHED,
            EventType.WORKLOG_ADDED,
            EventType.WORKLOG_UPDATED,
            EventType.WORKLOG_DELETED);

    private static final Set<EventType> PROJECT_EVENTS = Set.of(
            EventType.PROJECT_CREATED,
            EventType.PROJECT_UPDATED,
            EventType.PROJECT_ARCHIVED,
            EventType.MEMBER_ADDED,
            EventType.MEMBER_UPDATED,
            EventType.MEMBER_REMOVED);

    private static final Set<EventType> USER_EVENTS = EnumSet.of(
            EventType.USER_INVITED,
            EventType.USER_SIGNUP_EXISTING_ACCOUNT,
            EventType.USER_ACTIVATED,
            EventType.USER_EMAIL_CHANGED,
            EventType.USER_BLOCKED,
            EventType.USER_UNBLOCKED,
            EventType.PERSONAL_ACCESS_TOKEN_CREATED,
            EventType.PERSONAL_ACCESS_TOKEN_REVOKED);

    TaskaEventRegistry(
            @Value("${kafka.topics.issue-events}") String issueTopic,
            @Value("${kafka.topics.project-events}") String projectTopic,
            @Value("${kafka.topics.user-events}") String userTopic
    ) {
        this.issueTopic = issueTopic;
        this.projectTopic = projectTopic;
        this.userTopic = userTopic;
    }

    TaskaEvent validate(String topic, TaskaEvent event) {
        TaskaEventContract.requireValid(event);
        if (issueTopic.equals(topic)) {
            require(event, "issue-service", EventScopeKind.TENANT, ISSUE_EVENTS);
        } else if (projectTopic.equals(topic)) {
            require(event, "project-service", EventScopeKind.TENANT, PROJECT_EVENTS);
        } else if (userTopic.equals(topic)) {
            require(event, "auth-service", EventScopeKind.GLOBAL_USER, USER_EVENTS);
        } else {
            throw new IllegalArgumentException("Unregistered event topic");
        }
        return event;
    }

    private static void require(
            TaskaEvent event, String producer, EventScopeKind scope, Set<EventType> allowedTypes) {
        if (!producer.equals(event.producerService())
                || scope != event.scopeKind()
                || !allowedTypes.contains(EventType.fromValue(event.eventType()))) {
            throw new IllegalArgumentException("Event producer, topic and scope do not match registry");
        }
    }
}
