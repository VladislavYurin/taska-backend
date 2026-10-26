package ru.taska.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("taska.mcp")
public record McpProperties(
    URI gatewayUrl,
    Duration connectTimeout,
    Duration responseTimeout,
    int maxResponseBytes,
    List<String> allowedOrigins,
    Auth auth) {
  public McpProperties {
    if (gatewayUrl == null
        || !("http".equals(gatewayUrl.getScheme()) || "https".equals(gatewayUrl.getScheme()))
        || gatewayUrl.getHost() == null
        || gatewayUrl.getUserInfo() != null
        || gatewayUrl.getQuery() != null
        || gatewayUrl.getFragment() != null
        || !(gatewayUrl.getPath().isEmpty() || "/".equals(gatewayUrl.getPath()))) {
      throw new IllegalArgumentException("MCP gateway URL must be an HTTP origin");
    }
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || connectTimeout.toMillis() > Integer.MAX_VALUE
        || responseTimeout == null
        || responseTimeout.isNegative()
        || responseTimeout.isZero()
        || maxResponseBytes < 1024
        || maxResponseBytes > 2097152) {
      throw new IllegalArgumentException("Invalid MCP upstream limits");
    }
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    for (String origin : allowedOrigins) {
      URI value = URI.create(origin);
      if (!("http".equals(value.getScheme()) || "https".equals(value.getScheme()))
          || value.getHost() == null
          || value.getUserInfo() != null
          || !value.getPath().isEmpty()
          || value.getQuery() != null
          || value.getFragment() != null) {
        throw new IllegalArgumentException("MCP allowed origins must be exact HTTP origins");
      }
    }
  }

  public record Auth(String host, int port, Duration deadline) {
    public Auth {
      if (host == null
          || host.isBlank()
          || port < 1
          || port > 65535
          || deadline == null
          || deadline.isZero()
          || deadline.isNegative()) {
        throw new IllegalArgumentException("Invalid MCP auth service configuration");
      }
    }
  }
}
