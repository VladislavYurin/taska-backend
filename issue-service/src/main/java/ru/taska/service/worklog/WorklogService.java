package ru.taska.service.worklog;

import reactor.core.publisher.Mono;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;

import java.util.List;
import java.util.UUID;

/**
 * Сервис для работы с записями о затраченном времени (worklog) по задачам.
 *
 * <p>Перед каждой операцией проверяется, что задача существует, а у пользователя
 * есть нужная роль в проекте. При нарушении возвращается {@link Mono#error}.</p>
 */
public interface WorklogService {

    /**
     * Добавляет запись о затраченном времени к задаче.
     *
     * @param requestId   идентификатор запроса (для логирования)
     * @param nodeId      идентификатор узла (для логирования)
     * @param issueId     идентификатор задачи
     * @param actorUserId идентификатор пользователя, выполняющего действие
     * @param createWorklogDto данные новой записи
     * @return {@link Mono} с созданной записью, или ошибкой, если данные некорректны,
     *         задача не найдена или у пользователя недостаточно прав
     */
    Mono<Worklog> addIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            CreateWorklogDto createWorklogDto
    );

    /**
     * Обновляет запись о затраченном времени.
     *
     * <p>Набор допустимых ролей зависит от автора: автор записи и остальные
     * пользователи проверяются по разным правилам.</p>
     *
     * @param requestId   идентификатор запроса (для логирования)
     * @param nodeId      идентификатор узла (для логирования)
     * @param issueId     идентификатор задачи, к которой относится запись
     * @param worklogId   идентификатор обновляемой записи
     * @param actorUserId идентификатор пользователя, выполняющего действие
     * @param updateWorklogDto новые значения полей
     * @return {@link Mono} с обновлённой записью, или ошибкой, если данные некорректны,
     *         задача или запись не найдены, запись не принадлежит задаче
     *         либо у пользователя недостаточно прав
     */
    Mono<Worklog> updateIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId,
            UpdateWorklogDto updateWorklogDto
    );

    /**
     * Возвращает список активных записей о затраченном времени по задаче.
     *
     * @param requestId   идентификатор запроса (для логирования)
     * @param nodeId      идентификатор узла (для логирования)
     * @param issueId     идентификатор задачи
     * @param actorUserId идентификатор пользователя, выполняющего действие
     * @return {@link Mono} со списком записей, или ошибкой, если задача не найдена
     *         либо у пользователя недостаточно прав
     */
    Mono<List<Worklog>> listIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

    /**
     * Удаляет запись о затраченном времени.
     *
     * <p>Набор допустимых ролей зависит от автора: автор записи и остальные
     * пользователи проверяются по разным правилам.</p>
     *
     * @param requestId   идентификатор запроса (для логирования)
     * @param nodeId      идентификатор узла (для логирования)
     * @param issueId     идентификатор задачи, к которой относится запись
     * @param worklogId   идентификатор удаляемой записи
     * @param actorUserId идентификатор пользователя, выполняющего действие
     * @return {@link Mono} с удалённой записью, или ошибкой, если запись не найдена,
     *         не принадлежит задаче либо у пользователя недостаточно прав
     */
    Mono<Worklog> deleteIssueWorklog(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID worklogId,
            UUID actorUserId
    );
}