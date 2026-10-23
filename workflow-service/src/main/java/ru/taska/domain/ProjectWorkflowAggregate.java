package ru.taska.domain;

import java.util.List;

/**
 * Workflow, по которому работает проект, и типы задач проекта, которые по нему работают.
 *
 * @param workflow   workflow со всеми статусами и всеми переходами, включая скрытые
 * @param issueTypes типы задач проекта, привязанные к этому workflow
 * @param shared     общий default workflow: он не принадлежит проекту и не меняется
 */
public record ProjectWorkflowAggregate(
        WorkflowAggregate workflow,
        List<IssueType> issueTypes,
        boolean shared
) {
}
