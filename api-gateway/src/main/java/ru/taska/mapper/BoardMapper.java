package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.domain.dto.BoardColumnDto;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowStatusDto;

import java.util.List;
import java.util.UUID;

/**
 * Компонент-маппер для преобразования моделей REST API доски проекта.
 * <p>
 * Отвечает за создание DTO ответа Board API на основе уже подготовленных
 * данных. Не содержит бизнес-логики группировки, сортировки или валидации.
 */
@Component
public class BoardMapper {

    /**
     * Формирует REST DTO ответа Board API.
     *
     * @param projectId идентификатор проекта
     * @param issueType тип задач доски
     * @param columns список подготовленных колонок доски
     * @return объект {@link BoardResponseDto} для отправки клиенту
     */
    public BoardResponseDto toBoardResponse(
            UUID projectId,
            IssueTypeDto issueType,
            List<BoardColumnDto> columns
    ) {

        BoardResponseDto dto = new BoardResponseDto();
        dto.setProjectId(projectId);
        dto.setIssueType(issueType);
        dto.setColumns(columns);

        return dto;
    }

    /**
     * Преобразует описание статуса workflow и связанные с ним задачи
     * в DTO колонки доски.
     *
     * @param workflowStatus статус workflow проекта
     * @param issues задачи, относящиеся к данному статусу
     * @return заполненный объект {@link BoardColumnDto}
     */
    public BoardColumnDto toBoardColumn(
            WorkflowStatusDto workflowStatus,
            List<BoardIssueDto> issues
    ) {

        BoardColumnDto dto = new BoardColumnDto();

        dto.setStatusKey(workflowStatus.getStatusKey());
        dto.setName(workflowStatus.getName());
        dto.setCategory(workflowStatus.getCategory());
        dto.setSortOrder(workflowStatus.getSortOrder());
        dto.setIssues(issues);

        return dto;
    }
}