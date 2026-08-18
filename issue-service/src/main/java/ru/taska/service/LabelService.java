package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;

/**
 * Сервис для управления метками задач.
 */
public interface LabelService {

    /**
     * Создает метку на проекте
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса
     * @return dto ответа создание метки на проекте
     */
    Mono<LabelResponses.ProjectLabelInfo> createProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.CreateProjectLabelRequestDto requestDto
    );

    /**
     * Обновляет метку на проекте
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса
     * @return dto ответа обновления метки проекта
     */
    Mono<LabelResponses.ProjectLabelInfo> updateProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.UpdateProjectLabelRequestDto requestDto
    );

    /**
     * Удаляет метку из проекта
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса
     * @return dto ответа удаление метки из проекта
     */
    Mono<LabelResponses.DeleteProjectLabelResponseDto> deleteProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.DeleteProjectLabelRequestDto requestDto
    );

    /**
     * Добавляет метку к задаче
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса
     * @return dto ответа добавления метки к задаче
     */
    Mono<LabelResponses.AddIssueLabelResponseDto> addIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.AddIssueLabelRequestDto requestDto
    );

    /**
     * Открепляет метку от задачи
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса
     * @return dto ответа открепления метки от задачи
     */
    Mono<LabelResponses.RemoveIssueLabelResponseDto> removeIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.RemoveIssueLabelRequestDto requestDto
    );

    /**
     * Получает список меток, прикрепленных к задаче
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса списка меток задачи
     * @return dto ответа списка меток задачи
     */
    Mono<LabelResponses.ListIssueLabelResponseDto> listIssueLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListIssueLabelsRequestDto requestDto
    );

    /**
     * Получает список меток, доступных на проекте
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param requestDto  dto запроса списка меток проекта
     * @return dto ответа списка меток проекта
     */
    Mono<LabelResponses.ListProjectLabelResponseDto> listProjectLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListProjectLabelsRequestDto requestDto
    );

}
