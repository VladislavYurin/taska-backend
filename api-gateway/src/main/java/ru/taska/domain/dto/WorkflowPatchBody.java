package ru.taska.domain.dto;

import java.util.Map;
import java.util.List;

public interface WorkflowPatchBody {
    default List<String> getIssueTypes() { return null; }
    default String getName() { return null; }
    default WorkflowStatusCategoryDto getCategory() { return null; }
    default Integer getSortOrder() { return null; }
    default Boolean getHidden() { return null; }
    default Integer getVersion() { return null; }
    default Map<String, String> getStatusMappings() { return Map.of(); }
}
