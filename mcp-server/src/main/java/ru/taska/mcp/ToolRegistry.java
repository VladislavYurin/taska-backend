package ru.taska.mcp;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** One reviewed registry shared by transport preflight and SDK registration. */
@Component
public class ToolRegistry {
  private final List<ToolRegistration> tools;
  private final Map<String, ToolRegistration> byName;

  public ToolRegistry(ObjectProvider<ToolContributor> contributors) {
    tools =
        contributors
            .orderedStream()
            .flatMap(contributor -> contributor.tools().stream())
            .sorted(Comparator.comparing(tool -> tool.definition().name()))
            .toList();
    var index = new LinkedHashMap<String, ToolRegistration>();
    for (var registration : tools) {
      var definition = registration.definition();
      if (!definition.name().matches("[a-z][a-z0-9_]{0,63}")
          || !("read".equals(definition.scope()) || "write".equals(definition.scope()))
          || definition.readOnly() != "read".equals(definition.scope()))
        throw new IllegalStateException("Invalid MCP tool registration");
      if (index.putIfAbsent(definition.name(), registration) != null)
        throw new IllegalStateException("Duplicate MCP tool: " + definition.name());
    }
    byName = Map.copyOf(index);
  }

  public List<ToolRegistration> tools() {
    return tools;
  }

  public ToolRegistration find(String name) {
    return byName.get(name);
  }
}
