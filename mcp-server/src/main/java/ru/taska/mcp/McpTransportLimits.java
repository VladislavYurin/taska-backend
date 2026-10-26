package ru.taska.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taska.mcp.transport")
public record McpTransportLimits(@DefaultValue("65536") int maxRequestBytes) {
  public McpTransportLimits {
    if (maxRequestBytes < 1024 || maxRequestBytes > 65536)
      throw new IllegalArgumentException("MCP requests must be bounded to at most 64 KiB");
  }
}
