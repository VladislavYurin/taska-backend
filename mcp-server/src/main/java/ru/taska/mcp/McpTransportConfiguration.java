package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.Map;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class McpTransportConfiguration {
  @Bean
  WebFluxStatelessServerTransport webFluxStatelessServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
    return WebFluxStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(mapper))
        .messageEndpoint("/mcp")
        .contextExtractor(
            request ->
                McpTransportContext.create(
                    Map.of(McpRequestContext.KEY, request.attributes().get(McpRequestContext.KEY))))
        .build();
  }
}
