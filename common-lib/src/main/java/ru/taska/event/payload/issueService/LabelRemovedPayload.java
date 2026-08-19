package ru.taska.event.payload.issueService;

import java.util.UUID;

public record LabelRemovedPayload(
        UUID issueId,
        String labelName,
        UUID deletedBy
) {
}
