package ru.taska.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ResourceCatalog {
  @Bean
  List<AsyncResourceTemplateSpecification> taskaResourceTemplates(ResourceService resources) {
    return List.of(
        template(
            "taska://issues/{key}",
            "issue",
            "Карточка задачи в Markdown. " + ResourceMarkdown.WARNING,
            resources),
        template(
            "taska://projects/{key}",
            "project",
            "Workflow, типы, метки и участники проекта в Markdown. " + ResourceMarkdown.WARNING,
            resources));
  }

  private AsyncResourceTemplateSpecification template(
      String uri, String name, String description, ResourceService resources) {
    return new AsyncResourceTemplateSpecification(
        McpSchema.ResourceTemplate.builder(uri, name)
            .description(description)
            .mimeType("text/markdown")
            .build(),
        (context, request) -> resources.read(context, request.uri()));
  }

  @Bean
  @Primary
  McpStatelessServerTransport taskaResourceListTransport(
      WebFluxStatelessServerTransport transport, ResourceService resources) {
    return new ResourceListTransport(transport, resources);
  }
}
