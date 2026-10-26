package ru.taska.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Explicit snapshot exporter. CI compares; it never silently changes the MCP contract. */
public final class ReadSchemaExporter {
  private ReadSchemaExporter() {}

  public static void main(String[] args) throws IOException {
    Path root = Path.of(args.length == 0 ? "." : args[0]);
    String json = export(root.resolve("api-gateway/src/main/resources/static/openapi.yml"));
    Path destination = root.resolve("mcp-server/src/main/resources/schemas/read-tools.json");
    Files.createDirectories(destination.getParent());
    Files.writeString(destination, json);
  }

  static String export(Path source) throws IOException {
    Map<String, Object> api;
    try (var input = Files.newInputStream(source)) {
      api = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
    }
    Map<String, Object> sourceSchemas = cast(cast(api.get("components")).get("schemas"));
    var definitions = new java.util.TreeMap<String, Object>();
    Map<String, Object> page = integer(0, Integer.MAX_VALUE, 0);
    Map<String, Object> size = integer(1, 50, 20);
    Map<String, Object> project = string(1, 32);
    Map<String, Object> issue = string(1, 64);
    Map<String, Object> user = string(1, 320);
    Map<String, Object> type = new LinkedHashMap<>(string(2, 32));
    type.put("pattern", "^[A-Z][A-Z0-9_]{1,31}$");
    Map<String, Object> priority =
        Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH"));
    Map<String, Object> paging = props("page", page, "page_size", size);
    Map<String, Object> windows =
        props(
            "text_offset", integer(0, Integer.MAX_VALUE, 0), "text_limit", integer(1, 8192, 4000));
    add(
        definitions,
        sourceSchemas,
        "whoami",
        Map.of(),
        List.of(),
        object(
            props(
                "user",
                ref("ValidateAccessTokenResponseDto"),
                "token_kind",
                Map.of("type", "string", "enum", List.of("PAT", "OAUTH")),
                "scopes",
                array(Map.of("type", "string")),
                "expires_at",
                Map.of("type", "string", "format", "date-time")),
            List.of("user", "token_kind", "scopes", "expires_at")));
    add(
        definitions,
        sourceSchemas,
        "list_projects",
        paging,
        List.of(),
        object(
            props(
                "items",
                array(ref("ProjectResponseDto")),
                "page",
                integer(0, Integer.MAX_VALUE, 0),
                "total_count",
                Map.of("type", "integer", "minimum", 0),
                "has_more",
                Map.of("type", "boolean")),
            List.of("items", "page", "total_count", "has_more")));
    add(
        definitions,
        sourceSchemas,
        "get_project",
        props("project_key", project),
        List.of("project_key"),
        ref("ProjectContextResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "search_issues",
        merge(
            paging,
            props(
                "query",
                string(0, 500),
                "project_key",
                project,
                "status",
                string(1, 64),
                "assignee",
                user,
                "reporter",
                user,
                "priority",
                priority,
                "issue_type",
                type)),
        List.of(),
        ref("SearchIssuesResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "get_issue",
        merge(windows, props("issue_key", issue)),
        List.of("issue_key"),
        ref("IssueDetailsWithHistoryResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "list_comments",
        merge(paging, windows, props("issue_key", issue)),
        List.of("issue_key"),
        ref("CommentsListResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "list_worklogs",
        merge(paging, windows, props("issue_key", issue)),
        List.of("issue_key"),
        ref("ListIssueWorklogsResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "get_board",
        props(
            "project_key",
            project,
            "issue_type",
            type,
            "assignee",
            user,
            "label",
            string(1, 100),
            "priority",
            priority,
            "query",
            string(0, 500),
            "include_done",
            Map.of("type", "boolean", "default", false),
            "page_size_per_column",
            integer(1, 50, 20)),
        List.of("project_key"),
        ref("BoardResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "search_users",
        merge(props("query", string(3, 100), "page", integer(0, 1000, 0), "page_size", size)),
        List.of("query"),
        ref("UserDirectoryResponseDto"));
    add(
        definitions,
        sourceSchemas,
        "list_notifications",
        merge(paging, props("unread_only", Map.of("type", "boolean", "default", false))),
        List.of(),
        ref("NotificationListResponseDto"));
    return JsonMapper.builder()
            .build()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(definitions)
        + "\n";
  }

  private static void add(
      Map<String, Object> target,
      Map<String, Object> source,
      String name,
      Map<String, Object> inputs,
      List<String> required,
      Map<String, Object> data) {
    var input = object(inputs, required);
    input.put("additionalProperties", false);
    input.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    var closure = new Closure(source);
    Object converted = closure.convert(data);
    Map<String, Object> error =
        object(
            props(
                "code",
                string(1, 64),
                "message",
                string(1, 2000),
                "details",
                object(
                    props(
                        "reason",
                        string(1, 64),
                        "required_scope",
                        Map.of("type", "string", "enum", List.of("read", "write")),
                        "retry_after_seconds",
                        Map.of("type", "integer", "minimum", 0),
                        "current_version",
                        Map.of("type", "integer", "minimum", 0),
                        "current_status",
                        string(0, 64),
                        "candidates",
                        array(
                            object(
                                props(
                                    "id",
                                    string(1, 100),
                                    "displayName",
                                    string(0, 320),
                                    "email",
                                    string(0, 320)),
                                List.of("id"))),
                        "allowed_values",
                        array(string(0, 500))),
                    List.of())),
            List.of("code", "message", "details"));
    Map<String, Object> output =
        object(
            props(
                "request_id",
                string(1, 128),
                "truncated",
                Map.of("type", "boolean"),
                "data",
                converted,
                "error",
                error,
                "context",
                object(
                    props(
                        "project_key",
                        string(0, 32),
                        "project_name",
                        Map.of("type", "string"),
                        "issue_key",
                        string(0, 64),
                        "issue_summary",
                        Map.of("type", "string")),
                    List.of()),
                "text_windows",
                array(
                    object(
                        props(
                            "path",
                            Map.of("type", "string"),
                            "offset",
                            Map.of("type", "integer", "minimum", 0),
                            "total_chars",
                            Map.of("type", "integer", "minimum", 0),
                            "next_offset",
                            Map.of("type", List.of("integer", "null"), "minimum", 0)),
                        List.of("path", "offset", "total_chars", "next_offset")))),
            List.of("request_id", "truncated"));
    output.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    output.put(
        "oneOf",
        List.of(
            Map.of("required", List.of("data"), "not", Map.of("required", List.of("error"))),
            Map.of("required", List.of("error"), "not", Map.of("required", List.of("data")))));
    output.put("$defs", closure.definitions);
    target.put(name, Map.of("inputSchema", input, "outputSchema", output));
  }

  private static final class Closure {
    final Map<String, Object> source;
    final Map<String, Object> definitions = new java.util.TreeMap<>();

    Closure(Map<String, Object> source) {
      this.source = source;
    }

    Object convert(Object value) {
      Map<String, Object> schema = cast(value);
      Map<String, Object> result = new LinkedHashMap<>();
      schema.forEach(
          (key, property) -> {
            switch (key) {
              case "$ref" -> {
                String prefix = "#/components/schemas/";
                if (!property.toString().startsWith(prefix))
                  throw new IllegalArgumentException("External schema ref");
                String name = property.toString().substring(prefix.length());
                if (!definitions.containsKey(name)) {
                  definitions.put(name, Map.of());
                  Object sourceSchema = source.get(name);
                  if (sourceSchema == null)
                    throw new IllegalArgumentException("Missing schema " + name);
                  definitions.put(name, convert(sourceSchema));
                }
                result.put("$ref", "#/$defs/" + name);
              }
              case "properties" -> {
                Map<String, Object> properties = new LinkedHashMap<>();
                cast(property).forEach((name, child) -> properties.put(name, convert(child)));
                result.put(key, properties);
              }
              case "items", "not" -> result.put(key, convert(property));
              case "oneOf", "allOf", "anyOf" ->
                  result.put(key, ((List<?>) property).stream().map(this::convert).toList());
              case "additionalProperties" ->
                  result.put(key, property instanceof Map<?, ?> ? convert(property) : property);
              case "format" -> {
                if (Set.of("date", "date-time", "uuid", "uri", "email").contains(property))
                  result.put(key, property);
              }
              case "type",
                  "title",
                  "description",
                  "required",
                  "enum",
                  "default",
                  "minimum",
                  "maximum",
                  "multipleOf",
                  "minLength",
                  "maxLength",
                  "pattern",
                  "minItems",
                  "maxItems",
                  "uniqueItems",
                  "readOnly",
                  "writeOnly",
                  "deprecated" ->
                  result.put(key, property);
              default -> {
                /* OpenAPI annotations and x-* fields are not MCP schema vocabulary. */
              }
            }
          });
      if (result.containsKey("properties") && !result.containsKey("type"))
        result.put("type", "object");
      if (Boolean.TRUE.equals(schema.get("nullable")))
        return Map.of("anyOf", List.of(result, Map.of("type", "null")));
      return result;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }

  static Map<String, Object> props(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
    return map;
  }

  @SafeVarargs
  private static Map<String, Object> merge(Map<String, Object>... maps) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (var item : maps) map.putAll(item);
    return map;
  }

  private static Map<String, Object> string(int min, int max) {
    return props("type", "string", "minLength", min, "maxLength", max);
  }

  private static Map<String, Object> integer(int min, int max, int fallback) {
    return props("type", "integer", "minimum", min, "maximum", max, "default", fallback);
  }

  private static Map<String, Object> ref(String name) {
    return Map.of("$ref", "#/components/schemas/" + name);
  }

  private static Map<String, Object> array(Object items) {
    return Map.of("type", "array", "items", items);
  }

  private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
    return props("type", "object", "properties", properties, "required", required);
  }
}
