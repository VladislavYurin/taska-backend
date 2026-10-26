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
  org.springframework.ai.mcp.customizer.McpStatelessAsyncServerCustomizer
      taskaProtocolValidation() {
    // ToolExecutor uses the SDK schema validator and throws INVALID_PARAMS before the handler.
    // Avoid the SDK's duplicate early validator, which wraps invalid params in a successful RPC
    // result.
    return specification -> specification.validateToolInputs(false);
  }

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
