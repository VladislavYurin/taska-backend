package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResourcePromptSnapshotTest {
  @Test
  void frozenResourcesAndRussianPromptTemplatesMatchPublicDefinitions() throws Exception {
    var mapper = JsonMapper.builder().build();
    var contract = new LinkedHashMap<String, Object>();
    contract.put("protocolVersion", "2025-11-25");
    contract.put(
        "resourceTemplates",
        new ResourceCatalog()
            .taskaResourceTemplates(null).stream().map(value -> value.resourceTemplate()).toList());
    contract.put("prompts", new PromptCatalog().definitions());
    contract.put(
        "promptTemplates",
        Map.of(
            "triage_my_issues",
            PromptCatalog.TRIAGE,
            "standup",
            PromptCatalog.STANDUP,
            "decompose_issue",
            PromptCatalog.DECOMPOSE));
    var actual = mapper.valueToTree(contract);
    Path snapshot = Path.of("src/main/resources/schemas/resources-prompts.json");
    if (Boolean.getBoolean("updateMcpResourcePromptSnapshot"))
      ReadSchemaExporter.writeSnapshot(snapshot, mapper.writeValueAsString(actual));
    assertThat(mapper.readTree(Files.readString(snapshot)))
        .as("Review resource/prompt contract changes and explicitly regenerate the snapshot")
        .isEqualTo(actual);
    assertThat(mapper.writeValueAsString(actual))
        .doesNotContain("ttlMs", "cacheScope", "subscribe");
  }
}
