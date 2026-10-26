package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReadSchemaExportTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void reviewedSnapshotMatchesReachableOpenApiClosure() throws Exception {
    Path root = Path.of("..").toAbsolutePath().normalize();
    JsonNode actual =
        mapper.readTree(
            ReadSchemaExporter.export(
                root.resolve("api-gateway/src/main/resources/static/openapi.yml")));
    Path snapshot = root.resolve("mcp-server/src/main/resources/schemas/read-tools.json");
    if (Boolean.getBoolean("updateMcpReadSchemas"))
      ReadSchemaExporter.writeSnapshot(snapshot, mapper.writeValueAsString(actual));
    JsonNode saved = mapper.readTree(Files.readString(snapshot));
    assertThat(actual)
        .as("OpenAPI changed: explicitly review and re-export MCP read schemas")
        .isEqualTo(saved);
    assertThat(actual).hasSize(10);
    actual
        .properties()
        .forEach(
            tool -> {
              JsonNode input = tool.getValue().get("inputSchema");
              assertThat(input.path("additionalProperties").asBoolean()).isFalse();
              JsonNode output = tool.getValue().get("outputSchema");
              checkRefs(output, output.get("$defs"));
            });
  }

  @Test
  void nullableReferencedDtosAreStandaloneAndValidateNull() throws Exception {
    JsonNode schemas = mapper.readTree(getClass().getResourceAsStream("/schemas/read-tools.json"));
    JsonNode output = schemas.path("list_worklogs").path("outputSchema");
    JsonNode nullable = output.path("$defs").path("NullableUserSummaryDto");
    assertThat(nullable.path("anyOf")).hasSize(2);
    assertThat(nullable.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = mapper.convertValue(output, Map.class);
    Map<String, Object> worklog =
        Map.of(
            "id",
            "11111111-1111-4111-8111-111111111111",
            "issueId",
            "11111111-1111-4111-8111-111111111111",
            "projectId",
            "11111111-1111-4111-8111-111111111111",
            "authorUserId",
            "11111111-1111-4111-8111-111111111111",
            "spentMinutes",
            5,
            "workDate",
            "2026-10-20",
            "createdAt",
            "2026-10-20T10:00:00Z");
    var mutable = new java.util.LinkedHashMap<>(worklog);
    mutable.put("author", null);
    mutable.put("comment", null);
    assertThat(
            McpJsonDefaults.getSchemaValidator()
                .validate(
                    schema,
                    Map.of(
                        "request_id",
                        "schema-test",
                        "truncated",
                        false,
                        "data",
                        Map.of(
                            "items",
                            java.util.List.of(mutable),
                            "page",
                            0,
                            "pageSize",
                            20,
                            "totalCount",
                            1,
                            "hasMore",
                            false)))
                .valid())
        .isTrue();
  }

  private void checkRefs(JsonNode node, JsonNode definitions) {
    if (node.isObject()) {
      if (node.has("$ref")) {
        String ref = node.get("$ref").asText();
        assertThat(ref).startsWith("#/$defs/");
        assertThat(definitions.has(ref.substring("#/$defs/".length()))).as(ref).isTrue();
      }
      node.properties().forEach(value -> checkRefs(value.getValue(), definitions));
    } else if (node.isArray()) node.forEach(value -> checkRefs(value, definitions));
  }
}
