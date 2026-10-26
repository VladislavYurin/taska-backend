package ru.taska.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class HistoryParticipantsTest {
  @Test
  void onlyTopLevelServerOwnedIdentityFieldsAreResolved() {
    var payload = JsonNodeFactory.instance.objectNode();
    var expected = new java.util.LinkedHashSet<UUID>();
    for (String field :
        new String[] {
          "previousAssigneeId",
          "assigneeId",
          "reporterId",
          "authorUserId",
          "uploadedBy",
          "createdBy",
          "deletedBy",
          "deletedByUserId"
        }) {
      UUID id = UUID.randomUUID();
      payload.put(field, id.toString());
      expected.add(id);
    }
    UUID watcher = UUID.randomUUID();
    payload
        .putArray("watcherIds")
        .add(watcher.toString())
        .add(watcher.toString())
        .add("not-a-uuid")
        .add(7)
        .addNull();
    expected.add(watcher);
    UUID privateId = UUID.randomUUID();
    payload.put("body", privateId.toString());
    payload.put("summary", privateId.toString());
    payload.put("tokenId", privateId.toString());
    payload.putObject("changes").put("assigneeId", privateId.toString());
    assertThat(HistoryParticipants.ids(payload)).containsExactlyElementsOf(expected);
  }

  @Test
  void absentMalformedAndNonStringFieldsDoNotIntroduceProfiles() {
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("assigneeId", "1-1-1-1-1");
    payload.put("previousAssigneeId", 42);
    payload.putNull("reporterId");
    payload.putObject("createdBy").put("id", UUID.randomUUID().toString());
    payload.put("watcherIds", UUID.randomUUID().toString());
    assertThat(HistoryParticipants.ids(payload)).isEmpty();
    assertThat(HistoryParticipants.ids(null)).isEmpty();
    assertThat(HistoryParticipants.ids(JsonNodeFactory.instance.arrayNode())).isEmpty();
  }
}
