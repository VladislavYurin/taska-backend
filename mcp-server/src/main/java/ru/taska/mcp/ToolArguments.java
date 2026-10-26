package ru.taska.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ToolArguments(Map<String, Object> values) {
  public ToolArguments {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
  }

  public boolean has(String name) {
    return values.containsKey(name);
  }

  public String string(String name) {
    return (String) values.get(name);
  }

  public String key(String name) {
    String value = string(name);
    if (value == null || value.isBlank()) throw invalid("Укажите " + name + ".");
    return value.trim().toUpperCase(Locale.ROOT);
  }

  public int integer(String name, int fallback) {
    Number value = (Number) values.get(name);
    return value == null ? fallback : Math.toIntExact(value.longValue());
  }

  public boolean bool(String name, boolean fallback) {
    return values.get(name) instanceof Boolean value ? value : fallback;
  }

  public static McpError invalid(String message) {
    return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
  }
}
