package ru.taska.domain.projection;

import java.util.UUID;

public record ProjectIssueCountRow(UUID projectId, long issueCount) {
}
