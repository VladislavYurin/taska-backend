package ru.taska.domain.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO для проверки наличия проекта и membership.
 * Из репозитория получаем DTO, проверяем project_id и actorUserId, при успехе DTO мапится в Project
 * @param id - project_id
 * @param project_key
 * @param name
 * @param created_by
 * @param created_at
 * @param updated_at
 * @param archived_at
 * @param user_id - actorUserId
 */
    @Builder
    public record ProjectCheckMembershipDto(
        UUID id,
        String project_key,
        String name,
        UUID created_by,
        Instant created_at,
        Instant updated_at,
        Instant archived_at,
        UUID user_id
    ){}
