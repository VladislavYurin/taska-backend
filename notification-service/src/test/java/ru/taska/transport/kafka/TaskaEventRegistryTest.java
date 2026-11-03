package ru.taska.transport.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.event.EventScopeKind;
import ru.taska.event.TaskaEvent;
import ru.taska.event.TaskaEventContract;

class TaskaEventRegistryTest {

    private final TaskaEventRegistry registry =
            new TaskaEventRegistry("issue.events", "project.events", "user.events");

    @Test
    void acceptsRegisteredTenantProducer() {
        UUID tenantId = UUID.randomUUID();
        TaskaEvent event = event("issue-service", EventScopeKind.TENANT, tenantId, tenantId);

        assertThat(registry.validate("issue.events", event)).isSameAs(event);
    }

    @Test
    void rejectsMissingTenantBeforeHandlerCanCreateEffects() {
        TaskaEvent event = event("issue-service", EventScopeKind.TENANT, null, null);

        assertThatThrownBy(() -> registry.validate("issue.events", event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProducerTopicMismatch() {
        UUID tenantId = UUID.randomUUID();
        TaskaEvent event = event("project-service", EventScopeKind.TENANT, tenantId, tenantId);

        assertThatThrownBy(() -> registry.validate("issue.events", event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event producer, topic and scope do not match registry");
    }

    private static TaskaEvent event(
            String producer, EventScopeKind scopeKind, UUID tenantId, UUID scopeId) {
        return TaskaEvent.builder()
                .id(UUID.randomUUID())
                .scopeKind(scopeKind)
                .tenantId(tenantId)
                .scopeId(scopeId)
                .aggregateType("ISSUE")
                .aggregateId(UUID.randomUUID())
                .eventType("IssueCreated")
                .producerService(producer)
                .schemaVersion(TaskaEventContract.SCHEMA_VERSION)
                .build();
    }
}
