package ru.taska.mcp;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Only normalized client identifiers survive; arbitrary client-supplied text is never retained. */
public record McpCallMetadata(String clientName, String clientVersion, String traceparent) {
  private static final Set<String> CLIENTS =
      Set.of(
          "claude-desktop",
          "claude-code",
          "chatgpt",
          "inspector",
          "cursor",
          "taska-test",
          "unknown",
          "other");
  public static final McpCallMetadata NONE = new McpCallMetadata("unknown", null, null);

  public McpCallMetadata {
    clientName = clientName == null ? "unknown" : clientName.toLowerCase(Locale.ROOT);
    if (clientName.length() > 64 || !CLIENTS.contains(clientName)) clientName = "other";
    if (clientVersion != null
        && (clientVersion.length() > 32
            || !clientVersion.matches("[0-9]{1,5}(?:\\.[0-9]{1,5}){0,3}"))) clientVersion = null;
    if (traceparent != null
        && (!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
            || traceparent.substring(3, 35).equals("0".repeat(32))
            || traceparent.substring(36, 52).equals("0".repeat(16)))) traceparent = null;
  }

  static McpCallMetadata from(JsonNode meta) {
    if (meta == null || !meta.isObject()) return NONE;
    JsonNode client = meta.path("clientInfo");
    return new McpCallMetadata(
        client.path("name").asText("other"),
        client.path("version").asText(null),
        meta.path("traceparent").asText(null));
  }
}
