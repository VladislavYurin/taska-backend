package ru.taska.domain;

import java.util.List;

public record BoardIssuePageData(List<BoardIssueData> issues, long totalCount, boolean hasMore) {}
