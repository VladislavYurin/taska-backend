package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Public descriptions, complete schemas and annotations are reviewed as one versioned contract. */
class McpCatalogSnapshotTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  static JsonNode toolContract(JsonMapper mapper) {
    var beans = new DefaultListableBeanFactory();
    var gateway = mock(GatewayClient.class);
    var schemas = new ToolSchemas(mapper);
    beans.registerSingleton("read", new ReadTools(gateway, schemas, mapper));
    beans.registerSingleton(
        "write", new WriteTools(gateway, mock(WriteToolSupport.class), schemas, mapper));
    var registry = new ToolRegistry(beans.getBeanProvider(ToolContributor.class));
    return mapper.valueToTree(
        new TaskaToolCatalog()
            .taskaTools(registry, null).stream().map(spec -> spec.tool()).toList());
  }

  @Test
  void catalogMatchesReviewedContract() throws Exception {
    var actual = mapper.createObjectNode();
    actual.put("catalogVersion", "1");
    actual.set("tools", toolContract(mapper));
    actual.set(
        "prompts",
        mapper.valueToTree(
            new PromptCatalog()
                .definitions().stream()
                    .sorted(
                        java.util.Comparator.comparing(
                            io.modelcontextprotocol.spec.McpSchema.Prompt::name))
                    .toList()));
    actual.set(
        "resourceTemplates",
        mapper.valueToTree(
            new ResourceCatalog()
                .taskaResourceTemplates(null).stream()
                    .map(spec -> spec.resourceTemplate())
                    .toList()));
    actual.set(
        "promptTemplates",
        mapper.valueToTree(
            java.util.Map.of(
                "triage_my_issues",
                PromptCatalog.TRIAGE,
                "standup",
                PromptCatalog.STANDUP,
                "decompose_issue",
                PromptCatalog.DECOMPOSE)));
    Path snapshot = Path.of("src/test/resources/contracts/catalog-v1.json");
    if (Boolean.getBoolean("updateMcpCatalogSnapshot")) {
      Files.createDirectories(snapshot.getParent());
      Files.writeString(
          snapshot, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
    }
    assertThat(snapshot)
        .as("Review catalog and explicitly run -DupdateMcpCatalogSnapshot=true")
        .exists();
    assertThat(actual)
        .as(
            "Public MCP contract changed; review the tool/prompt/resource diff before updating"
                + " snapshot")
        .isEqualTo(mapper.readTree(Files.readString(snapshot)));
    assertThat(actual.path("tools")).hasSize(22);
  }
}
