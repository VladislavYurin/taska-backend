package ru.taska.event.payload.issueService;

import java.util.UUID;

public record LabelAddedPayload(
        UUID issueId,
        String labelName,
        UUID createdBy
) {
}