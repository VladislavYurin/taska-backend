package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WriteSchemaExportTest {
  @Test
  void everyProtectedMutationUsesWriteScopeInAuthoritativeRestContract() throws Exception {
    Path path = Path.of("../api-gateway/src/main/resources/static/openapi.yml");
    Map<String, Object> api;
    try (var input = Files.newInputStream(path)) {
      api =
          new org.yaml.snakeyaml.Yaml(
                  new org.yaml.snakeyaml.constructor.SafeConstructor(
                      new org.yaml.snakeyaml.LoaderOptions()))
              .load(input);
    }
    Map<?, ?> paths = (Map<?, ?>) api.get("paths");
    for (GatewayMutation operation : GatewayMutation.values()) {
      Map<?, ?> route = (Map<?, ?>) paths.get(operation.path());
      assertThat(route).as(operation.path()).isNotNull();
      Map<?, ?> method =
          (Map<?, ?>) route.get(operation.method().name().toLowerCase(java.util.Locale.ROOT));
      assertThat(method.get("x-pat-scope")).isEqualTo("write");
      assertThat(method.get("security")).isNotNull();
    }
  }

  @Test
  void writeSnapshotMatchesReviewedDtoClosureAndHasAllTwelveOperations() throws Exception {
    var mapper = JsonMapper.builder().build();
    Path root = Path.of("..").toAbsolutePath().normalize();
    String exported =
        WriteSchemaExporter.export(
            root.resolve("api-gateway/src/main/resources/static/openapi.yml"));
    Path snapshot = root.resolve("mcp-server/src/main/resources/schemas/write-tools.json");
    if (Boolean.getBoolean("updateMcpWriteSchemas"))
      ReadSchemaExporter.writeSnapshot(snapshot, exported);
    var actual = mapper.readTree(exported);
    assertThat(mapper.readTree(Files.readString(snapshot))).isEqualTo(actual);
    assertThat(actual).hasSize(12);
    assertThat(exported).doesNotContain("#/components/", "nullable", "tokenId", "tokenName");
    actual
        .properties()
        .forEach(
            tool ->
                assertThat(
                        tool.getValue()
                            .path("inputSchema")
                            .path("additionalProperties")
                            .asBoolean())
                    .isFalse());
  }
}
