package ru.taska.domain.aggregate;

import java.util.List;
import ru.taska.domain.entity.Worklog;

/** One database statement snapshot, including the count for an empty/out-of-range page. */
public record WorklogRows(List<Worklog> items, long totalCount) {}
