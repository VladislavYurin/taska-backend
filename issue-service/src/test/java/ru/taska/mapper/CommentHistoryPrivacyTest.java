package ru.taska.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.domain.entity.IssueHistory;
import tools.jackson.databind.ObjectMapper;

class CommentHistoryPrivacyTest {

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = IssueEventType.class, names = {"COMMENT_CREATED", "COMMENT_UPDATED", "COMMENT_DELETED"})
    void bothHistoryApis_redactLegacyTextWithoutMutatingStoredPayload(IssueEventType type) {
        var payload = json.createObjectNode().put("commentId", "comment-id")
                .put("body", "secret").put("oldBody", "old-secret").put("newBody", "new-secret");
        var history = IssueHistory.builder().id(UUID.randomUUID()).issueId(UUID.randomUUID())
                .actorUserId(UUID.randomUUID()).occurredAt(Instant.now()).eventType(type).payload(payload).build();

        var legacy = new IssueMapper(json).toIssueHistoryProto(history);
        var aggregated = new IssueDetailsMapperImpl().toHistoryEvent(history);

        for (var response : List.of(legacy, aggregated)) {
            assertThat(response.getPayload()).isEqualTo("{\"commentId\":\"comment-id\"}");
            assertThat(response.getId()).isEqualTo(history.getId().toString());
            assertThat(response.getActorUserId()).isEqualTo(history.getActorUserId().toString());
        }
        assertThat(payload.path("body").asText()).isEqualTo("secret");
    }

    @ParameterizedTest
    @EnumSource(value = IssueEventType.class, names = {"CREATED", "UPDATED", "WORKLOG_UPDATED"})
    void unrelatedHistory_keepsItsPayload(IssueEventType type) {
        var history = IssueHistory.builder().eventType(type)
                .payload(json.createObjectNode().put("body", "preserved")).build();
        assertThat(IssueMapperUtils.historyPayload(history)).isEqualTo(history.getPayload().toString());
    }

    @ParameterizedTest
    @EnumSource(value = IssueEventType.class, names = {"COMMENT_CREATED", "COMMENT_UPDATED", "COMMENT_DELETED"})
    void malformedLegacyCommentPayload_isNotExposed(IssueEventType type) {
        var history = IssueHistory.builder().eventType(type)
                .payload(json.getNodeFactory().stringNode("secret")).build();
        assertThat(IssueMapperUtils.historyPayload(history)).isEqualTo("{}");
    }
}
