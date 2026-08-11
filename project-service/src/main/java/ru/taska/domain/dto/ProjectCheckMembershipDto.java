package ru.taska.domain.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO для проверки наличия проекта и membership.
 * Из репозитория получаем DTO, проверяем project_id и actorUserId, при успехе DTO мапится в Project
 */
    @Builder
    public record ProjectCheckMembershipDto(
        UUID project_id,
        String project_key,
        String name,
        UUID created_by,
        Instant created_at,
        Instant updated_at,
        Instant archived_at,
        UUID user_id
    ){}
