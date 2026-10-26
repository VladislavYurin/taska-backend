package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import ru.taska.api.common.v1.UserContext;

/** Credentials live only in the request context and are never serialized into tool results. */
public record McpRequestContext(String requestId, String bearerToken, UserContext user) {
  public static final String KEY = McpRequestContext.class.getName();

  public static McpRequestContext from(McpTransportContext context) {
    Object value = context.get(KEY);
    if (value instanceof McpRequestContext request) {
      return request;
    }
    throw new IllegalStateException("Authenticated MCP request context is missing");
  }

  @Override
  public String toString() {
    return "McpRequestContext[requestId=" + requestId + "]";
  }
}
