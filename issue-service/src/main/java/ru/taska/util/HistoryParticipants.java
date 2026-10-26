package ru.taska.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Only server-owned identity fields are interpreted; arbitrary UUIDs in user text are ignored. */
public final class HistoryParticipants {
  private HistoryParticipants() {}

  public static Set<UUID> ids(JsonNode payload) {
    Set<UUID> result = new LinkedHashSet<>();
    if (payload == null || !payload.isObject()) return result;
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
        }) add(result, payload.get(field));
    JsonNode watchers = payload.get("watcherIds");
    if (watchers != null && watchers.isArray()) for (JsonNode value : watchers) add(result, value);
    return result;
  }

  private static void add(Set<UUID> result, JsonNode value) {
    if (value == null || !value.isString()) return;
    String text = value.asText();
    if (!text.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return;
    result.add(UUID.fromString(text));
  }
}
