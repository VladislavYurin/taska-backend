package ru.taska.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Value
@Builder
public class AuditEventDto {
    private String requestId; // nullable

    @NotNull
    private UUID actorUserId;

    @NotBlank
    private String actorLogin;

    @NotNull
    private JsonNode actorRoles;

    @NotBlank
    private String action;

    @NotBlank
    private String targetService;

    @NotBlank
    private String targetTable;

    @NotBlank
    private String targetId;

    private JsonNode oldValue; // nullable
    private JsonNode newValue; // nullable

    @NotBlank(message = "Reason is required")
    private String reason;
}
