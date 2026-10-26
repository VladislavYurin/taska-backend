package ru.taska.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Projects reviewed DTO fields, windows Unicode text and caps the complete serialized tool result.
 */
@Component
public class ToolResultFactory {
  private static final Set<String> TEXT = Set.of("description", "body", "comment");
  private static final Set<String> DETAILS =
      Set.of(
          "reason",
          "required_scope",
          "retry_after_seconds",
          "current_version",
          "current_status",
          "candidates",
          "allowed_values");
  private final JsonMapper mapper;
  private final ToolLimits limits;

  public ToolResultFactory(JsonMapper mapper, ToolLimits limits) {
    this.mapper = mapper;
    this.limits = limits;
  }

  public McpSchema.CallToolResult success(ToolInvocation call, ToolData data) {
    JsonNode schema = mapper.valueToTree(call.definition().outputSchema());
    JsonNode projected =
        project(data.data(), schema.get("properties").get("data"), schema.get("$defs"), true, 0);
    List<Map<String, Object>> windows = new ArrayList<>();
    int offset = call.arguments().integer("text_offset", 0);
    int limit =
        Math.min(call.arguments().integer("text_limit", limits.textLimit()), limits.maxTextLimit());
    window(projected, "/data", offset, limit, windows, 0);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("request_id", call.context().requestId());
    envelope.put("truncated", data.truncated() || !windows.isEmpty());
    envelope.put("data", projected);
    if (!data.context().isEmpty()) envelope.put("context", data.context());
    if (!windows.isEmpty()) envelope.put("text_windows", windows);
    if (!McpJsonDefaults.getSchemaValidator()
        .validate(call.definition().outputSchema(), envelope)
        .valid()) {
      return error(
          call.context(),
          new GatewayToolException(
              "upstream_error", "Taska вернула ответ, несовместимый с контрактом."));
    }
    String text = call.definition().name() + ": " + codePoints(data.summary(), 800);
    if (!windows.isEmpty()) {
      Object next =
          windows.stream()
              .map(value -> value.get("next_offset"))
              .filter(java.util.Objects::nonNull)
              .findFirst()
              .orElse(null);
      if (next != null
          && call.definition().inputSchema().get("properties") instanceof Map<?, ?> properties
          && properties.containsKey("text_offset"))
        text += " Текст обрезан; продолжение: text_offset=" + next + ".";
      else text += " Показан ограниченный предпросмотр пользовательского текста.";
    }
    var result = result(envelope, text, false);
    return mapper.writeValueAsBytes(result).length > limits.maxResultBytes()
        ? error(call.context(), tooLarge())
        : result;
  }

  public McpSchema.CallToolResult error(McpRequestContext context, GatewayToolException failure) {
    Map<String, Object> details = new LinkedHashMap<>();
    failure
        .details()
        .forEach(
            (key, value) -> {
              if (DETAILS.contains(key)) details.put(key, value);
            });
    String message = codePoints(failure.getMessage(), 512);
    var result =
        result(
            Map.of(
                "request_id",
                context.requestId(),
                "truncated",
                false,
                "error",
                Map.of("code", failure.code(), "message", message, "details", details)),
            message,
            true);
    if (mapper.writeValueAsBytes(result).length <= limits.maxResultBytes()) return result;
    var error = tooLarge();
    return result(
        Map.of(
            "request_id",
            context.requestId(),
            "truncated",
            false,
            "error",
            Map.of("code", error.code(), "message", error.getMessage(), "details", Map.of())),
        error.getMessage(),
        true);
  }

  private static GatewayToolException tooLarge() {
    return new GatewayToolException(
        "response_too_large",
        "Ответ слишком велик. Уточните запрос или уменьшите page_size, page_size_per_column либо"
            + " text_limit.");
  }

  private McpSchema.CallToolResult result(Map<String, Object> content, String text, boolean error) {
    return McpSchema.CallToolResult.builder()
        .isError(error)
        .structuredContent(content)
        .addTextContent(text)
        .build();
  }

  private JsonNode project(
      JsonNode node, JsonNode schema, JsonNode definitions, boolean required, int depth) {
    if (depth > 64)
      throw new GatewayToolException("upstream_error", "Слишком глубокая структура ответа Taska.");
    schema = resolve(schema, definitions);
    if (node == null || node.isNull()) {
      if (allowsNull(schema, definitions)) return mapper.nullNode();
      if (!required) return null;
      throw new GatewayToolException(
          "upstream_error", "В обязательном поле ответа Taska отсутствует значение.");
    }
    if (schema != null && schema.has("anyOf")) {
      for (JsonNode candidate : schema.get("anyOf")) {
        if (!"null".equals(candidate.path("type").asText()))
          return project(node, candidate, definitions, required, depth + 1);
      }
    }
    if (node.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      for (JsonNode child : node)
        result.add(
            project(
                child, schema == null ? null : schema.get("items"), definitions, true, depth + 1));
      return result;
    }
    if (node.isObject()) {
      ObjectNode result = mapper.createObjectNode();
      JsonNode properties = schema == null ? null : schema.get("properties");
      for (var property : node.properties()) {
        if (properties != null && !properties.has(property.getKey())) continue;
        JsonNode childSchema =
            properties == null
                ? schema == null ? null : schema.get("additionalProperties")
                : properties.get(property.getKey());
        boolean childRequired =
            schema != null
                && schema.has("required")
                && java.util.stream.StreamSupport.stream(
                        schema.get("required").spliterator(), false)
                    .anyMatch(value -> property.getKey().equals(value.asText()));
        JsonNode child =
            project(property.getValue(), childSchema, definitions, childRequired, depth + 1);
        if (child != null) result.set(property.getKey(), child);
      }
      return result;
    }
    return node.deepCopy();
  }

  private static JsonNode resolve(JsonNode schema, JsonNode definitions) {
    for (int depth = 0; schema != null && depth < 64; depth++) {
      if (schema.has("$ref")) {
        String ref = schema.get("$ref").asText();
        if (!ref.startsWith("#/$defs/") || definitions == null)
          throw new IllegalStateException("Non-local MCP schema reference");
        schema = definitions.get(ref.substring("#/$defs/".length()));
      } else if (schema.has("allOf") && schema.get("allOf").size() == 1)
        schema = schema.get("allOf").get(0);
      else return schema;
    }
    return schema;
  }

  private static boolean allowsNull(JsonNode schema, JsonNode definitions) {
    schema = resolve(schema, definitions);
    if (schema == null || schema.isBoolean() || schema.isEmpty()) return true;
    JsonNode type = schema.get("type");
    if (type != null
        && ("null".equals(type.asText())
            || type.isArray()
                && java.util.stream.StreamSupport.stream(type.spliterator(), false)
                    .anyMatch(t -> "null".equals(t.asText())))) return true;
    if (schema.has("anyOf"))
      for (JsonNode child : schema.get("anyOf")) if (allowsNull(child, definitions)) return true;
    return type == null && !schema.has("properties") && !schema.has("enum");
  }

  private void window(
      JsonNode node,
      String path,
      int offset,
      int limit,
      List<Map<String, Object>> windows,
      int depth) {
    if (depth > 64)
      throw new GatewayToolException("upstream_error", "Слишком глубокая структура ответа Taska.");
    if (node.isObject()) {
      var fields = new ArrayList<>(node.properties());
      for (var field : fields) {
        String childPath = path + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
        if (TEXT.contains(field.getKey()) && field.getValue().isString()) {
          String value = field.getValue().asText();
          int total = value.codePointCount(0, value.length());
          int start = Math.min(offset, total);
          int end = (int) Math.min((long) start + limit, total);
          if (start != 0 || end != total) {
            ((ObjectNode) node)
                .put(
                    field.getKey(),
                    value.substring(
                        value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end)));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", childPath);
            meta.put("offset", start);
            meta.put("total_chars", total);
            meta.put("next_offset", end < total ? end : null);
            windows.add(meta);
          }
        } else window(field.getValue(), childPath, offset, limit, windows, depth + 1);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++)
        window(node.get(i), path + "/" + i, offset, limit, windows, depth + 1);
    }
  }

  static String codePoints(String value, int max) {
    if (value == null) return "";
    return value.codePointCount(0, value.length()) <= max
        ? value
        : value.substring(0, value.offsetByCodePoints(0, max));
  }
}
