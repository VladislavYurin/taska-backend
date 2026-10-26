package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** All resource business reads reuse reviewed tools, including ACL, admission, timeout and caps. */
@Component
public class ResourceService {
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final ResourceMarkdown markdown;
  private final JsonMapper mapper;
  private final ToolLimits limits;

  public ResourceService(
      ToolRegistry registry,
      ToolExecutor executor,
      ResourceMarkdown markdown,
      JsonMapper mapper,
      ToolLimits limits) {
    this.registry = registry;
    this.executor = executor;
    this.markdown = markdown;
    this.mapper = mapper;
    this.limits = limits;
  }

  public Mono<McpSchema.ListResourcesResult> list(McpTransportContext context) {
    return execute(context, "search_issues", Map.of("page", 0, "page_size", 20))
        .map(
            envelope -> {
              List<McpSchema.Resource> resources = new ArrayList<>();
              for (JsonNode issue : envelope.path("data").path("items")) {
                if (resources.size() == 20) break;
                var uri = ResourceUri.parse("taska://issues/" + issue.path("issueKey").asText(""));
                resources.add(
                    McpSchema.Resource.builder()
                        .uri(uri.uri())
                        .name(uri.key())
                        .title(
                            "Недоверенное название: "
                                + ToolResultFactory.codePoints(
                                    issue.path("summary").asText(""), 255))
                        .description(ResourceMarkdown.WARNING)
                        .mimeType("text/markdown")
                        .build());
              }
              var result = new McpSchema.ListResourcesResult(List.copyOf(resources), null);
              cap(result);
              return result;
            });
  }

  public Mono<McpSchema.ReadResourceResult> read(McpTransportContext context, String value) {
    return Mono.defer(
        () -> {
          ResourceUri uri = ResourceUri.parse(value);
          return execute(
                  context,
                  uri.issue() ? "get_issue" : "get_project",
                  Map.of(uri.issue() ? "issue_key" : "project_key", uri.key()))
              .map(
                  envelope -> {
                    var result =
                        new McpSchema.ReadResourceResult(
                            List.of(
                                new McpSchema.TextResourceContents(
                                    uri.uri(), "text/markdown", markdown.render(uri, envelope))),
                            Map.of(
                                "request_id",
                                envelope.path("request_id").asText(""),
                                "truncated",
                                envelope.path("truncated").asBoolean(false)));
                    cap(result);
                    return result;
                  });
        });
  }

  private Mono<JsonNode> execute(
      McpTransportContext context, String tool, Map<String, Object> arguments) {
    var registration = registry.find(tool);
    if (registration == null)
      return Mono.error(new IllegalStateException("Required reviewed read tool is missing"));
    return executor
        .execute(registration, context, new McpSchema.CallToolRequest(tool, arguments))
        .map(
            result -> {
              JsonNode envelope = mapper.valueToTree(result.structuredContent());
              if (Boolean.TRUE.equals(result.isError())) {
                var error = envelope.path("error");
                if ("not_found".equals(error.path("code").asText(""))) throw ResourceUri.notFound();
                Map<String, Object> safe = new java.util.LinkedHashMap<>();
                safe.put("code", error.path("code").asText("upstream_error"));
                safe.put("request_id", envelope.path("request_id").asText(""));
                if (error.path("details").path("retry_after_seconds").isIntegralNumber())
                  safe.put(
                      "retry_after_seconds",
                      error.path("details").path("retry_after_seconds").asInt());
                throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                    .message(
                        "Ресурс временно недоступен; используйте request_id и повторите чтение"
                            + " позже.")
                    .data(safe)
                    .build();
              }
              return envelope;
            });
  }

  private void cap(Object value) {
    if (mapper.writeValueAsBytes(value).length > limits.maxResultBytes())
      throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
          .message(
              "Ресурс слишком велик; используйте инструменты с ограниченной страницей/окном"
                  + " текста.")
          .data(Map.of("code", "response_too_large"))
          .build();
  }
}
