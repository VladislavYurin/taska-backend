package ru.taska.domain.dto;

import java.util.Map;

public interface WorkflowPatchBody {
    default String getName() { return null; }
    default WorkflowStatusCategoryDto getCategory() { return null; }
    default Integer getSortOrder() { return null; }
    default Boolean getHidden() { return null; }
    default Integer getVersion() { return null; }
    default Map<String, String> getStatusMappings() { return Map.of(); }
}
