package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolResultFallbackTest {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ToolResultFactory factory = new ToolResultFactory(mapper, ToolLimits.defaults());
  private final McpRequestContext context =
      new McpRequestContext(
          "fallback-test", McpAuthenticationFilterTest.TOKEN, McpAuthenticationFilterTest.user());

  @Test
  void errorJsonContainsSameSafePartialResultAndConflictStateAsStructuredEnvelope() {
    var result =
        factory.error(
            context,
            new GatewayToolException(
                "partial_success",
                "Перечитайте TAS-3.",
                Map.of(
                    "created_issue_key",
                    "TAS-3",
                    "current_version",
                    7,
                    "current_status",
                    "IN_PROGRESS",
                    "completed_steps",
                    List.of("created"),
                    "remaining_steps",
                    List.of("labels"),
                    "privateToken",
                    context.bearerToken())));
    JsonNode fallback = fallback(result);
    assertThat(fallback.path("error").path("details").path("created_issue_key").asText())
        .isEqualTo("TAS-3");
    assertThat(fallback.path("error").path("details").path("current_version").asInt()).isEqualTo(7);
    assertThat(fallback.path("error").path("details").path("remaining_steps").get(0).asText())
        .isEqualTo("labels");
    assertThat(mapper.writeValueAsString(result))
        .doesNotContain(context.bearerToken(), "privateToken");
    assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
        .isEqualTo("Перечитайте TAS-3.");
  }

  @Test
  void completeByteCapIncludesBothTextBlocksAndStructuredContent() {
    var registration = McpToolPolicyTest.registration(false, ignored -> null);
    var call =
        new ToolInvocation(registration.definition(), context, new ToolArguments(Map.of()), null);
    var result =
        factory.success(
            call,
            new ToolData(mapper.createObjectNode().put("ok", "x".repeat(70000)), "Read result"));
    assertThat(result.isError()).isTrue();
    assertThat(fallback(result).path("error").path("code").asText())
        .isEqualTo("response_too_large");
    assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(131072);
  }

  @Test
  void oversizedErrorDetailsAreCappedWithoutLeavingLargeJsonFallback() {
    var result =
        factory.error(
            context,
            new GatewayToolException(
                "invalid_argument",
                "Выберите пользователя.",
                Map.of("candidates", List.of("x".repeat(70000)))));
    assertThat(fallback(result).path("error").path("code").asText())
        .isEqualTo("response_too_large");
    assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(131072);
    assertThat(((McpSchema.TextContent) result.content().get(1)).text())
        .doesNotContain("x".repeat(1000));
  }

  @Test
  void minimumConfiguredCapStillFitsLongestAcceptedRequestId() {
    var small =
        new ToolResultFactory(
            mapper, new ToolLimits(java.time.Duration.ofSeconds(20), 1024, 4000, 8192));
    var longId = new McpRequestContext("r".repeat(128), context.bearerToken(), context.user());
    var result =
        small.error(
            longId,
            new GatewayToolException(
                "invalid_argument",
                "Уточните запрос.",
                Map.of("candidates", List.of("x".repeat(70000)))));
    assertThat(fallback(result).path("request_id").asText()).isEqualTo("r".repeat(128));
    assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(1024);
  }

  private JsonNode fallback(McpSchema.CallToolResult result) {
    assertThat(result.content()).hasSize(2);
    JsonNode text = mapper.readTree(((McpSchema.TextContent) result.content().get(1)).text());
    assertThat(text).isEqualTo((JsonNode) mapper.valueToTree(result.structuredContent()));
    return text;
  }
}
