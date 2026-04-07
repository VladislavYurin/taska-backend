package ru.taska.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InviteTokenResponse {
    private UUID id;
    private UUID userId;
    private String rawToken;
    private Instant expiresAt;
}
