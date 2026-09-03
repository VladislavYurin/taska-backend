package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO ответа после изменений статуса пользователя и полей Credential
 */
@Builder
public record UserCredentialStateResponseDto(
        UUID userId,
        String previousStatus,
        String currentStatus,
        OffsetDateTime changedAt,
        CredentialState oldCredentialState,
        CredentialState newCredentialState
) {
    /**
     * Состояние учётных данных (Credential)
     */
    @Builder
    public record CredentialState(
            int failedAttempts,
            OffsetDateTime lockedUntil,
            OffsetDateTime lastFailedAt
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
