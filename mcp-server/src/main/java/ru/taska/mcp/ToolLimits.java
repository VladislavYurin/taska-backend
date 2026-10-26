package ru.taska.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taska.mcp.tools")
public record ToolLimits(
    @DefaultValue("20s") Duration timeout,
    @DefaultValue("131072") int maxResultBytes,
    @DefaultValue("4000") int textLimit,
    @DefaultValue("8192") int maxTextLimit) {
  public ToolLimits {
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || maxResultBytes < 1024
        || maxResultBytes > 131072
        || textLimit < 1
        || maxTextLimit < textLimit
        || maxTextLimit > 8192) {
      throw new IllegalArgumentException("Invalid MCP tool limits");
    }
  }

  public static ToolLimits defaults() {
    return new ToolLimits(Duration.ofSeconds(20), 131072, 4000, 8192);
  }
}
