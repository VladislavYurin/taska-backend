package ru.taska.event;

import java.util.UUID;

/**
 * Информация о issue, извлечённая из payload Kafka-события.
 * Используется для заполнения структурированных полей уведомления для:
 * <ul>
 *   <li>построения URL на клиенте: /projects/{projectId}/issues/{issueId};</li>
 *   <li>отображения читаемого ключа в тексте уведомления: "API-12".</li>
 * </ul>
 *
 * <p>Все поля могут быть {@code null} для не-issue уведомлений
 * (USER_INVITED, MEMBER_ADDED, PROJECT_CREATED и т.д.).</p>
 */
public record IssueInfo(
        UUID issueId,
        String issueKey,
        UUID projectId
) {

    /**
     * Пустая ссылка — для уведомлений, не связанных с задачей.
     */
    public static IssueInfo empty() {
        return new IssueInfo(null, null, null);
    }

    /**
     * @return true, если хотя бы одно из полей заполнено
     */
    public boolean hasAnyField() {
        return issueId != null || (issueKey != null && !issueKey.isBlank()) || projectId != null;
    }
}
