package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Embedded;
import ru.taska.domain.entity.IssueLink;

/**
 * Данные связи задачи с целевой задачей.
 *
 * @param link связь между задачами
 * @param target целевая задача
 */
public record IssueLinkDetails(
        @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL)
        IssueLink link,
        @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL, prefix = "target_")
        TargetIssue target
){}
