package ru.taska.mcp;

import java.util.Map;

public record ToolDefinition(
    String name,
    String description,
    String scope,
    boolean readOnly,
    boolean idempotent,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema) {}
