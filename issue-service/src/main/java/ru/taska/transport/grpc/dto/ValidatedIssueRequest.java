package ru.taska.transport.grpc.dto;

import java.util.UUID;

public record ValidatedIssueRequest (
    String requestId,
    String nodeId,
    UUID issueId,
    UUID actorUserId
) { }

