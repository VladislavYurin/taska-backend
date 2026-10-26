package ru.taska.mcp;

public record ToolInvocation(
    ToolDefinition definition,
    McpRequestContext context,
    ToolArguments arguments,
    ToolResolver.Session resolver) {}
