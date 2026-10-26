package ru.taska.mcp;

public record ToolInvocation(
    ToolDefinition definition,
    McpRequestContext context,
    ToolArguments arguments,
    ToolResolver.Session resolver,
    ToolAuditTarget auditTarget) {
  public ToolInvocation(
      ToolDefinition definition,
      McpRequestContext context,
      ToolArguments arguments,
      ToolResolver.Session resolver) {
    this(
        definition,
        context,
        arguments,
        resolver,
        resolver == null ? new ToolAuditTarget() : resolver.auditTarget());
  }
}
