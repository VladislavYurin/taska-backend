package ru.taska.transport.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.event.EventScopeKind;
import ru.taska.event.TaskaEvent;
import ru.taska.event.TaskaEventContract;

/** Validates the trusted producer/topic/scope part of an incoming event contract. */
@Component
final class TaskaEventRegistry {

    private final String issueTopic;
    private final String projectTopic;
    private final String userTopic;

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
            require(event, "issue-service", EventScopeKind.TENANT);
        } else if (projectTopic.equals(topic)) {
            require(event, "project-service", EventScopeKind.TENANT);
        } else if (userTopic.equals(topic)) {
            require(event, "auth-service", EventScopeKind.GLOBAL_USER);
        } else {
            throw new IllegalArgumentException("Unregistered event topic");
        }
        return event;
    }

    private static void require(TaskaEvent event, String producer, EventScopeKind scope) {
        if (!producer.equals(event.producerService()) || scope != event.scopeKind()) {
            throw new IllegalArgumentException("Event producer, topic and scope do not match registry");
        }
    }
}
