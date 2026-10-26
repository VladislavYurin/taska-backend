package ru.taska.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taska.mcp.quota")
public record McpQuotaProperties(
    @DefaultValue("120") int tokenRead,
    @DefaultValue("20") int tokenWrite,
    @DefaultValue("240") int userRead,
    @DefaultValue("40") int userWrite,
    @DefaultValue("4") int tokenConcurrent,
    @DefaultValue("8") int userConcurrent,
    @DefaultValue("10000") int maxTokens,
    @DefaultValue("10000") int maxUsers,
    @DefaultValue("5m") Duration idleTtl) {
  public McpQuotaProperties {
    for (int value :
        new int[] {
          tokenRead,
          tokenWrite,
          userRead,
          userWrite,
          tokenConcurrent,
          userConcurrent,
          maxTokens,
          maxUsers
        }) {
      if (value < 1 || value > 100000) throw new IllegalArgumentException("Invalid MCP quota");
    }
    if (idleTtl == null
        || idleTtl.compareTo(Duration.ofMinutes(1)) < 0
        || idleTtl.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException("MCP idle TTL must preserve a full bucket refill");
    }
  }

  public static McpQuotaProperties defaults() {
    return new McpQuotaProperties(120, 20, 240, 40, 4, 8, 10000, 10000, Duration.ofMinutes(5));
  }
}
