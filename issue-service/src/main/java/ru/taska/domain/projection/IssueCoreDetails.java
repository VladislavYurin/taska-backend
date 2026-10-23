package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Embedded;
import ru.taska.domain.entity.Issue;

/**
 * Основные данные задачи, необходимые для формирования детальной информации.
 *
 * @param issue задача
 * @param commentCount количество комментариев
 * @param isWatching признак того, что текущий пользователь наблюдает за задачей
 */
public record IssueCoreDetails(
        @Embedded.Empty
        Issue issue,
        long commentCount,
        boolean isWatching
) {
}
