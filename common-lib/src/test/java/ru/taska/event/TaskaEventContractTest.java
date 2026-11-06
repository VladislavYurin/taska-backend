package ru.taska.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskaEventContractTest {

    @Test
    void sameAggregateInDifferentTenantsHasDifferentPartitionKey() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        assertThat(TaskaEventContract.partitionKey(tenantEvent(tenantA, aggregateId)))
                .isNotEqualTo(TaskaEventContract.partitionKey(tenantEvent(tenantB, aggregateId)));
    }

    @Test
    void tenantEventWithoutTenantIsRejectedInsteadOfUsingDefaultScope() {
        TaskaEvent event = tenantEvent(null, UUID.randomUUID());

        assertThatThrownBy(() -> TaskaEventContract.requireValid(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing scope id");
    }

    @Test
    void globalEventCannotCarryTenantId() {
        UUID userId = UUID.randomUUID();
        TaskaEvent event = baseEventBuilder(userId)
                .scopeKind(EventScopeKind.GLOBAL_USER)
                .scopeId(userId)
                .tenantId(UUID.randomUUID())
                .producerService("auth-service")
                .build();

        assertThatThrownBy(() -> TaskaEventContract.requireValid(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Global event must not contain tenant id");
    }

    private static TaskaEvent tenantEvent(UUID tenantId, UUID aggregateId) {
        return baseEventBuilder(aggregateId)
                .scopeKind(EventScopeKind.TENANT)
                .tenantId(tenantId)
                .scopeId(tenantId)
                .producerService("issue-service")
                .build();
    }

    private static TaskaEvent.TaskaEventBuilder baseEventBuilder(UUID aggregateId) {
        return TaskaEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("ISSUE")
                .aggregateId(aggregateId)
                .eventType("IssueCreated")
                .occurredAt(Instant.now())
                .schemaVersion(TaskaEventContract.SCHEMA_VERSION);
    }
}
