package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.TaskaEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class OutboxEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxEventMapper mapper = new OutboxEventMapper(objectMapper);

    private OutboxEvent event(String eventType, String aggregateType, UUID aggregateId, JsonNode payload) {
        return OutboxEvent.builder()
                          .id(UUID.randomUUID())
                          .aggregateType(aggregateType)
                          .aggregateId(aggregateId)
                          .eventType(eventType)
                          .payload(payload)
                          .build();
    }

    @Nested
    class ToTaskaEvent {

        @Test
        void shouldShapeProjectCreatedPayload() {
            UUID projectId = UUID.randomUUID();
            UUID createdBy = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of(
                    "projectId", projectId.toString(),
                    "projectKey", "ABC",
                    "name", "Test Project",
                    "createdBy", createdBy.toString()
            ));

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("ProjectCreated", "project", projectId, source));

            Assertions.assertThat(taskaEvent.eventType()).isEqualTo("ProjectCreated");
            JsonNode payload = taskaEvent.payload();
            Assertions.assertThat(payload.get("projectId").asString()).isEqualTo(projectId.toString());
            Assertions.assertThat(payload.get("projectKey").asString()).isEqualTo("ABC");
            Assertions.assertThat(payload.get("createdBy").asString()).isEqualTo(createdBy.toString());
            Assertions.assertThat(payload.has("name")).isFalse();
        }

        @Test
        void shouldShapeMemberAddedPayload() {
            UUID projectId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID addedBy = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of(
                    "projectId", projectId.toString(),
                    "userId", userId.toString(),
                    "role", "MEMBER",
                    "addedBy", addedBy.toString()
            ));

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("MemberAdded", "project", projectId, source));

            JsonNode payload = taskaEvent.payload();
            Assertions.assertThat(payload.get("projectId").asString()).isEqualTo(projectId.toString());
            Assertions.assertThat(payload.get("userId").asString()).isEqualTo(userId.toString());
            Assertions.assertThat(payload.get("role").asString()).isEqualTo("MEMBER");
            Assertions.assertThat(payload.get("addedBy").asString()).isEqualTo(addedBy.toString());
        }

        @Test
        void shouldShapeMemberRoleChangedPayload() {
            UUID projectId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of(
                    "projectId", projectId.toString(),
                    "userId", userId.toString(),
                    "role", "ADMIN"
            ));

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("MemberRoleChanged", "project", projectId, source));

            JsonNode payload = taskaEvent.payload();
            Assertions.assertThat(payload.get("userId").asString()).isEqualTo(userId.toString());
            Assertions.assertThat(payload.get("role").asString()).isEqualTo("ADMIN");
        }

        @Test
        void shouldShapeMemberRemovedPayload() {
            UUID projectId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of(
                    "projectId", projectId.toString(),
                    "userId", userId.toString()
            ));

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("MemberRemoved", "project", projectId, source));

            JsonNode payload = taskaEvent.payload();
            Assertions.assertThat(payload.get("projectId").asString()).isEqualTo(projectId.toString());
            Assertions.assertThat(payload.get("userId").asString()).isEqualTo(userId.toString());
        }

        @Test
        void shouldYieldNullField_whenSourceFieldMissing() {
            UUID projectId = UUID.randomUUID();
            Map<String, String> sourceMap = new HashMap<>();
            sourceMap.put("id", projectId.toString());
            sourceMap.put("projectKey", "ABC");

            JsonNode source = objectMapper.valueToTree(sourceMap);

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("ProjectCreated", "project", projectId, source));

            Assertions.assertThat(taskaEvent.payload().get("createdBy").isNull()).isTrue();
        }

        @Test
        void shouldPassThroughPayload_whenEventTypeUnsupported() {
            UUID aggregateId = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of("custom", "value"));

            TaskaEvent taskaEvent = mapper.toTaskaEvent(event("SomethingUnknown", "project", aggregateId, source));

            Assertions.assertThat(taskaEvent.payload().get("custom").asString()).isEqualTo("value");
        }
    }

    @Nested
    class ToTaskaEventJsonAsString {

        @Test
        void shouldReturnJsonWithEventType() {
            UUID projectId = UUID.randomUUID();
            JsonNode source = objectMapper.valueToTree(Map.of(
                    "id", projectId.toString(),
                    "projectKey", "ABC",
                    "createdBy", UUID.randomUUID().toString()
            ));

            String json = mapper.toTaskaEventJsonAsString(event("ProjectCreated", "project", projectId, source));

            Assertions.assertThat(json).contains("\"eventType\":\"ProjectCreated\"");
            Assertions.assertThat(json).contains("\"projectKey\":\"ABC\"");
        }
    }
}
