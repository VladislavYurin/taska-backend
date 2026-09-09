package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;

import java.util.UUID;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectAccessInfoDto;

public interface ProjectService {

    /**
     * Создает в БД новый проект с переданными параметрами.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param projectKey  ключ проекта
     * @param projectName имя проекта
     * @param userId      айди юзера, создающего проект
     * @return Mono<{@link Project}> с данными созданного проекта
     */
    Mono<Project> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId);

    /**
     * Возвращает проект из БД по Id
     *
     * @param projectId   айди проекта
     * @param actorUserId aйди актора, для проверки доступа (вхождения) в проект
     * @return Mono<{@link Project}> проект из БД по запросу
     */
    Mono<Project> getProject(String requestId, String nodeId, UUID projectId, UUID actorUserId);

    /**
     * Возвращает список всех проектов пользователя по его Id
     *
     * @param userId айди пользователя
     * @return Flux<{@link Project}> список проектов пользователя с перeданным в метод Id
     */
    Flux<Project> listMyProjects(String requestId, String nodeId, UUID userId);

    /**
     * Мягкое удаление проекта, установка поля archived_at в БД.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param projectId  айди проекта
     * @param userId      айди юзера, удаляющего проект
     * @return Mono<{@link Project}> с данными созданного проекта
     */
    Mono<Project> softDeleteProject(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId);

    /**
     * Проверяет доступ к проекту по полученным данным.
     *
     * @param requestId уникальный идентификатор запроса.
     * @param nodeId    уникальный идентификатор узла.
     * @param projectId уникальный идентификатор проекта.
     * @param userId    уникальный идентификатор участника проекта.
     * @return ({@link ProjectAccessInfoDto}) из трех элементов, содержащий:
     * <ul>
     * <li>{@code T1} ({@link ProjectRole}) — роль участника проекта</li>
     * <li>{@code T2} ({@link Boolean}) — флаг, сигнализирующий о том, является ли пользователь участником проекта</li>
     * <li>{@code T3} ({@link Boolean}) — флаг, сигнализирующий о том, существует ли такой проект</li>
     * <li>{@code T4} ({@link Boolean}) — флаг, сигнализирующий о том, не удален ли проект (soft delete)</li>
     * </ul>
     */
    Mono<ProjectAccessInfoDto> checkProjectAccess(String requestId, String nodeId, UUID projectId, UUID userId);

    /**
     * Возвращает ключ проекта по Id (без проверки прав).
     * Используется ТОЛЬКО для внутренних вызовов из других сервисов, которые уже проверили права доступа.
     * @param projectId айди проекта
     * @return Mono с ключом проекта
     */
    Mono<String> getProjectKeyByIdInternal(UUID projectId);
}
