package ru.taska.domain;

/**
 * Результат PATCH-обновления задачи.
 * <p>
 * {@code issue} содержит актуальное состояние задачи.</p>
 * {@code version_conflict == true} — версия из {@code If-Match} не совпала с текущей версией задачи в БД,
 * изменения не применены.
 * {@code issue} содержит текущее (неизменённое) состояние задачи. {@code version_conflict == false} —
 * изменения применены, {@code issue} содержит уже обновлённое состояние задачи.
 */
public record PatchIssueResult(Issue issue, boolean versionConflict) {
}
