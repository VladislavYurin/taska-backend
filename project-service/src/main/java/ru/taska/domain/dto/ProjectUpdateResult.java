package ru.taska.domain.dto;

import ru.taska.domain.Project;
import ru.taska.domain.ProjectRole;

/**
 * Результат {@code updateProject}: обновлённый проект и роль актора, которую сервис уже
 * проверил (ADMIN) — чтобы транспортный слой мог вернуть {@code currentUserRole} в ответе
 * без повторного запроса membership.
 */
public record ProjectUpdateResult(Project project, ProjectRole actorRole) {
}
