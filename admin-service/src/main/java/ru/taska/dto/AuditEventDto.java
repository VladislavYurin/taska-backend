package ru.taska.dto;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Value
@Builder
public class AuditEventDto {
    private String requestId;
    private UUID actorUserId;
    private String actorLogin;
    private JsonNode actorRoles;
    private String action;
    private String targetService;
    private String targetTable;
    private String targetId;
    private JsonNode oldValue;
    private JsonNode newValue;
    private String reason;
}
