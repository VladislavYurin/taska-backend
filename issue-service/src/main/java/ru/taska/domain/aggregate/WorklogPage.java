package ru.taska.domain.aggregate;

import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.entity.Worklog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A bounded page of active worklogs and profiles belonging only to that page. */
public record WorklogPage(
        List<Worklog> items,
        int page,
        int pageSize,
        long totalCount,
        Map<UUID, UserSummary> authorProfiles
) {
    public boolean hasMore() {
        return (long) page * pageSize + items.size() < totalCount;
    }
}
