package ru.taska.domain.dto.projectContext;

import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import java.util.List;
/**
 * DTO полного контекста проекта для GET /api/v1/projects/{projectId}/context
 * Собирается из project-service (сам проект, участники) +
 * issue-service (метки, счётчики) + workflow-service (workflows).
 */
public record ProjectContextDto (
        /// Собираем из project-service
        ProjectCheckMembershipDto project,

        /// Собираем из issue-service
        List<ProjectLabelDto> labels,

        /// Собираем из workflow-service
        List<ProjectWorkflowDto> workflows,

        /// Собираем из auth-service
        List<ProjectMemberDetailsDto> members
) {}
