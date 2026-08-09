package ru.taska.domain.dto;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * DTO над {@link ru.taska.domain.IssueLink} для загрузки из БД только необходимых полей.
 */
public record IssueLinkInfoDto(
        UUID id,
        UUID projectId
) {
}
