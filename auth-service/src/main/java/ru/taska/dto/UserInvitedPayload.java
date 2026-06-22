package ru.taska.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInvitedPayload {
    private UUID userId;
    private String email;
    private String rawInviteToken;
    private Instant expiresAt;
}
