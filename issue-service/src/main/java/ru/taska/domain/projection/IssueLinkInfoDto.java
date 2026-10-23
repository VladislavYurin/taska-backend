package ru.taska.domain.projection;

import ru.taska.domain.entity.IssueLink;

import java.util.UUID;

/**
 * DTO над {@link IssueLink} для загрузки из БД только необходимых полей.
 */
public record IssueLinkInfoDto(
        UUID id,
        UUID projectId
) {
}
