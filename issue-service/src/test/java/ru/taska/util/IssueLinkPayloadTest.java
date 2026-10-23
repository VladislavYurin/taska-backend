package ru.taska.util;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueLinkType;
import tools.jackson.databind.ObjectMapper;

class IssueLinkPayloadTest {
    @Test
    void linkPayloadsIncludeBothKeysAndKeepIdentifiers() {
        var serializer = new PayloadSerializer(new ObjectMapper());
        var issue = Issue.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID()).issueKey("ABC-1").build();
        var target = UUID.randomUUID();
        var actor = UUID.randomUUID();
        var watcher = UUID.randomUUID();
        var created = serializer.createIssueLinkCreatedPayload(issue,target,IssueLinkType.BLOCKS,actor,List.of(watcher),"ABC-2");
        var deleted = serializer.createIssueLinkDeletedPayload(issue,target,IssueLinkType.BLOCKS,actor,List.of(watcher),"ABC-2");
        for (var payload : List.of(created,deleted)) {
            assertThat(payload.get("issueKey").asString()).isEqualTo("ABC-1");
            assertThat(payload.get("targetIssueKey").asString()).isEqualTo("ABC-2");
            assertThat(payload.get("targetIssueId").asString()).isEqualTo(target.toString());
            assertThat(payload.get("watcherIds").get(0).asString()).isEqualTo(watcher.toString());
        }
    }
}
