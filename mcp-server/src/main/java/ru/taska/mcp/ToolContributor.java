package ru.taska.mcp;

import java.util.List;

/** Independent read/write contributors share execution, result limits and schema validation. */
public interface ToolContributor {
  List<ToolRegistration> tools();
}
