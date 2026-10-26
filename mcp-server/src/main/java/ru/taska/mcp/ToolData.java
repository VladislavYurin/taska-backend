package ru.taska.mcp;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public record ToolData(
    JsonNode data, Map<String, Object> context, String summary, boolean truncated) {
  public ToolData(JsonNode data, Map<String, Object> context, String summary) {
    this(data, context, summary, false);
  }

  public ToolData(JsonNode data, String summary) {
    this(data, Map.of(), summary, false);
  }
}
