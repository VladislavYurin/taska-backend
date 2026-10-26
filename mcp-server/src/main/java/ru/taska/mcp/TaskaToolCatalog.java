package ru.taska.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskaToolCatalog {
  @Bean
  List<AsyncToolSpecification> taskaTools(ToolRegistry registry, ToolExecutor executor) {
    var tools = registry.tools();
    var names = new HashSet<String>();
    return tools.stream()
        .map(
            registration -> {
              var definition = registration.definition();
              if (!names.add(definition.name()))
                throw new IllegalStateException("Duplicate MCP tool: " + definition.name());
              var tool =
                  McpSchema.Tool.builder(definition.name(), definition.inputSchema())
                      .description(definition.description())
                      .outputSchema(definition.outputSchema())
                      .annotations(
                          McpSchema.ToolAnnotations.builder()
                              .readOnlyHint(definition.readOnly())
                              .destructiveHint(false)
                              .openWorldHint(false)
                              .idempotentHint(definition.idempotent())
                              .build())
                      .build();
              return new AsyncToolSpecification(
                  tool, (transport, request) -> executor.execute(registration, transport, request));
            })
        .toList();
  }
}
