package ru.taska.domain.dto;

import java.util.UUID;

/**
 * Краткое представление пользователя, связанного с задачей.
 *
 * <p>Содержит данные пользователя, необходимые для отображения
 * исполнителя, автора задачи, наблюдателя, автора вложения и других
 * пользователей, связанных с задачей.</p>
 *
 * @param id идентификатор пользователя
 * @param displayName отображаемое имя пользователя
 * @param avatarUrl URL аватара пользователя
 */
public record UserSummary(
        UUID id,
        String displayName,
        String avatarUrl
) {
}
