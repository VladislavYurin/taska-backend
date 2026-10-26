package ru.taska.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  McpProperties.class,
  ToolLimits.class,
  McpQuotaProperties.class,
  McpTransportLimits.class
})
public class McpServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(McpServerApplication.class, args);
  }
}
