package ru.taska.mcp;

import static ru.taska.mcp.ReadSchemaExporter.add;
import static ru.taska.mcp.ReadSchemaExporter.array;
import static ru.taska.mcp.ReadSchemaExporter.integer;
import static ru.taska.mcp.ReadSchemaExporter.merge;
import static ru.taska.mcp.ReadSchemaExporter.object;
import static ru.taska.mcp.ReadSchemaExporter.props;
import static ru.taska.mcp.ReadSchemaExporter.ref;
import static ru.taska.mcp.ReadSchemaExporter.string;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/** V1 write schemas share the same standalone DTO closure exporter as read tools. */
public final class WriteSchemaExporter {
  private WriteSchemaExporter() {}

  public static void main(String[] args) throws IOException {
    Path root = Path.of(args.length == 0 ? "." : args[0]);
    ReadSchemaExporter.writeSnapshot(
        root.resolve("mcp-server/src/main/resources/schemas/write-tools.json"),
        export(root.resolve("api-gateway/src/main/resources/static/openapi.yml")));
  }

  @SuppressWarnings("unchecked")
  static String export(Path source) throws IOException {
    Map<String, Object> api;
    try (var input = Files.newInputStream(source)) {
      api = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
    }
    Map<String, Object> dtos =
        (Map<String, Object>) ((Map<?, ?>) api.get("components")).get("schemas");
    Map<String, Object> output = new java.util.TreeMap<>();
    Map<String, Object> key = string(1, 64), project = string(1, 32), user = string(1, 320);
    Map<String, Object> version = integer(1, Integer.MAX_VALUE, 1);
    version = new LinkedHashMap<>(version);
    version.remove("default");
    Map<String, Object> uuid =
        Map.of(
            "type",
            "string",
            "format",
            "uuid",
            "pattern",
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    Map<String, Object> type =
        props("type", "string", "pattern", "^[A-Z][A-Z0-9_]{1,31}$", "maxLength", 32);
    Map<String, Object> priority =
        Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH"));
    Map<String, Object> dates = Map.of("type", "string", "format", "date");
    Map<String, Object> nonnegative =
        Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE);
    Map<String, Object> labels =
        props(
            "type",
            "array",
            "items",
            string(1, 100),
            "minItems",
            0,
            "maxItems",
            20,
            "uniqueItems",
            true);
    add(
        output,
        dtos,
        "create_issue",
        props(
            "project_key",
            project,
            "summary",
            string(1, 255),
            "issue_type",
            type,
            "description",
            string(0, 32768),
            "priority",
            priority,
            "assignee",
            user,
            "labels",
            labels,
            "idempotency_key",
            uuid),
        List.of("project_key", "summary", "idempotency_key"),
        ref("IssueResponseDto"));
    Map<String, Object> changes =
        props(
            "summary",
            string(1, 255),
            "description",
            nullable(string(0, 32768)),
            "priority",
            priority,
            "issue_type",
            type,
            "story_points",
            nullable(Map.of("type", "number", "minimum", 0)),
            "start_date",
            nullable(dates),
            "due_date",
            nullable(dates),
            "original_estimate_minutes",
            nullable(nonnegative),
            "remaining_estimate_minutes",
            nullable(nonnegative));
    add(
        output,
        dtos,
        "update_issue",
        merge(changes, props("issue_key", key, "version", version)),
        List.of("issue_key"),
        ref("IssueResponseDto"));
    Map<String, Object> input =
        (Map<String, Object>) ((Map<?, ?>) output.get("update_issue")).get("inputSchema");
    input.put(
        "anyOf", changes.keySet().stream().map(name -> Map.of("required", List.of(name))).toList());
    add(
        output,
        dtos,
        "transition_issue",
        props("issue_key", key, "target_status", string(1, 64), "version", version),
        List.of("issue_key", "target_status"),
        ref("IssueWithHistoryResponseDto"));
    add(
        output,
        dtos,
        "assign_issue",
        props("issue_key", key, "assignee", nullable(user), "version", version),
        List.of("issue_key", "assignee"),
        ref("IssueResponseDto"));
    add(
        output,
        dtos,
        "add_comment",
        props("issue_key", key, "body", string(1, 10000)),
        List.of("issue_key", "body"),
        ref("CommentResponseDto"));
    add(
        output,
        dtos,
        "log_work",
        props(
            "issue_key",
            key,
            "spent_minutes",
            integer(1, 1440, 1),
            "work_date",
            dates,
            "comment",
            string(0, 2000)),
        List.of("issue_key", "spent_minutes", "work_date"),
        ref("IssueWorklogResponseDto"));
    for (String name : List.of("add_label", "remove_label"))
      add(
          output,
          dtos,
          name,
          props("issue_key", key, "label", string(1, 100)),
          List.of("issue_key", "label"),
          object(
              props(
                  "issue", ref("IssueResponseDto"), "labels", array(ref("IssueLabelResponseDto"))),
              List.of("issue", "labels")));
    add(
        output,
        dtos,
        "link_issues",
        props(
            "issue_key",
            key,
            "target_issue_key",
            key,
            "link_type",
            Map.of("type", "string", "enum", List.of("BLOCKS", "RELATES_TO", "DUPLICATES"))),
        List.of("issue_key", "target_issue_key", "link_type"),
        ref("IssueLinkResponseDto"));
    add(
        output,
        dtos,
        "watch_issue",
        props("issue_key", key),
        List.of("issue_key"),
        ref("WatchIssueResponseDto"));
    add(
        output,
        dtos,
        "unwatch_issue",
        props("issue_key", key),
        List.of("issue_key"),
        ref("UnwatchIssueResponseDto"));
    add(
        output,
        dtos,
        "mark_notifications_read",
        props("notification_id", uuid),
        List.of("notification_id"),
        object(
            props("notification_id", uuid, "read", Map.of("type", "boolean", "const", true)),
            List.of("notification_id", "read")));
    return JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(output)
        + "\n";
  }

  private static Map<String, Object> nullable(Object value) {
    return Map.of("anyOf", List.of(value, Map.of("type", "null")));
  }
}
