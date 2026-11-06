package ru.taska.event;

import java.util.Objects;
import java.util.UUID;

/** Shared validation and partition-key rules for the public event envelope. */
public final class TaskaEventContract {

    public static final String SCHEMA_VERSION = "2";

    private TaskaEventContract() {
    }

    public static TaskaEvent requireValid(TaskaEvent event) {
        Objects.requireNonNull(event, "event");
        require(event.id(), "event id");
        require(event.scopeKind(), "scope kind");
        require(event.scopeId(), "scope id");
        requireText(event.aggregateType(), "aggregate type");
        require(event.aggregateId(), "aggregate id");
        requireText(event.eventType(), "event type");
        requireText(event.producerService(), "producer service");
        if (!SCHEMA_VERSION.equals(event.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported event schema version");
        }

        if (event.scopeKind() == EventScopeKind.TENANT) {
            require(event.tenantId(), "tenant id");
            if (!event.tenantId().equals(event.scopeId())) {
                throw new IllegalArgumentException("Tenant id must match scope id");
            }
        } else if (event.tenantId() != null) {
            throw new IllegalArgumentException("Global event must not contain tenant id");
        }
        return event;
    }

    public static String partitionKey(TaskaEvent event) {
        requireValid(event);
        if (event.scopeKind() == EventScopeKind.TENANT) {
            return tenantPartitionKey(event.tenantId(), event.aggregateType(), event.aggregateId());
        }
        return globalPartitionKey(event.scopeKind(), event.scopeId(), event.aggregateId());
    }

    public static String tenantPartitionKey(UUID tenantId, String aggregateType, UUID aggregateId) {
        return "t:%s:%s:%s".formatted(
                require(tenantId, "tenant id"),
                requireText(aggregateType, "aggregate type"),
                require(aggregateId, "aggregate id"));
    }

    public static String globalPartitionKey(EventScopeKind scopeKind, UUID scopeId, UUID aggregateId) {
        if (scopeKind == null || scopeKind == EventScopeKind.TENANT) {
            throw new IllegalArgumentException("Global partition requires global scope kind");
        }
        return "g:%s:%s:%s".formatted(
                scopeKind,
                require(scopeId, "scope id"),
                require(aggregateId, "aggregate id"));
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label);
        }
        return value;
    }
}
