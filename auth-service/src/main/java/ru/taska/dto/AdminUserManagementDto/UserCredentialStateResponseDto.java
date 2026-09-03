package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;
import ru.taska.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO ответа после изменений статуса пользователя и полей Credential
 */
@Builder
public record UserCredentialStateResponseDto(
        UUID userId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant changedAt,
        CredentialState oldCredentialState,
        CredentialState newCredentialState
) {
    /**
     * Состояние учётных данных (Credential)
     */
    @Builder
    public record CredentialState(
            int failedAttempts,
            Instant lockedUntil,
            Instant lastFailedAt
    ) {
        public static CredentialState empty() {
            return CredentialState.builder()
                    .failedAttempts(0)
                    .lockedUntil(null)
                    .lastFailedAt(null)
                    .build();
        }
    }
}
