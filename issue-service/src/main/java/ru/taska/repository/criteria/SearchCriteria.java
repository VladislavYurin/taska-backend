package ru.taska.repository.criteria;

import lombok.Builder;
import lombok.Value;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class SearchCriteria {
    UUID projectId;
    List<UUID> projectIds;
    String statusKey;
    UUID assigneeId;
    UUID reporterId;
    String priority;
    String issueType;
    String searchQuery;
    Integer limit;
    Long offset;
    boolean count;

    public boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // Определяем, какой фильтр по проекту использовать
    public boolean hasProjectFilter() {
        return projectId != null || (projectIds != null && !projectIds.isEmpty());
    }

    // Получаем список projectIds (если указан один projectId, возвращаем как список)
    public List<UUID> getEffectiveProjectIds() {
        if (projectIds != null && !projectIds.isEmpty()) {
            return projectIds;
        }
        if (projectId != null) {
            return List.of(projectId);
        }
        return null;
    }
}