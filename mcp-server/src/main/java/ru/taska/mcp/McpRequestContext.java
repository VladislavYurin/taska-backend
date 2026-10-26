package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import ru.taska.api.common.v1.UserContext;

/** Credentials live only in the request context and are never serialized into tool results. */
public record McpRequestContext(
    String requestId,
    String bearerToken,
    UserContext user,
    McpCallMetadata metadata,
    McpAuthorizationKind authorizationKind) {
  public McpRequestContext(String requestId, String bearerToken, UserContext user) {
    this(requestId, bearerToken, user, McpCallMetadata.NONE, McpAuthorizationKind.PAT);
  }

  public McpRequestContext(
      String requestId, String bearerToken, UserContext user, McpCallMetadata metadata) {
    this(requestId, bearerToken, user, metadata, McpAuthorizationKind.PAT);
  }

  public McpRequestContext withMetadata(McpCallMetadata value) {
    return new McpRequestContext(requestId, bearerToken, user, value, authorizationKind);
  }

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
