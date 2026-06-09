package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.UsersProjectsResponse;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.ProjectSetting;

import java.util.UUID;

public interface ProjectService {

    /**
     * Создает проект на основе {@link ru.taska.api.project.v1.CreateProjectRequest}
     * Получает данные из header (requestId, nodeId) для логирования
     * и projectKey, projectName, userId для создания проекта
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param projectKey  ключ проекта
     * @param projectName имя проекта
     * @param userId      айди юзера, создающего проект
     * @return Mono<{@link ProjectResponse}> с соответствующими параметрами созданного проекта
     */
    Mono<ProjectResponse> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId);

    /**
     * Возвращает проект из БД на основе {@link ru.taska.api.project.v1.GetProjectRequest}
     * Получает projectId для поиска требуемого проекта в БД
     *
     * @param projectId айди проекта
     * @return Mono<{@link ProjectResponse}> проект из БД по запросу
     */
    Mono<ProjectResponse> getProject(String requestId, String nodeId, UUID projectId);

    /**
     * Возвращает список всех проектов пользователя на основе {@link ru.taska.api.project.v1.GetProjectRequest}
     * Получает userId для поиска всех проектов текущего пользователя в БД
     *
     * @param userId айди пользователя
     * @return Mono<{@link UsersProjectsResponse}> список проектов текущего пользователя
     */
    Mono<UsersProjectsResponse> listMyProjects(String requestId, String nodeId, UUID userId);

    /**
     * Сервисный метод для создания дефолтных настроек проекта
     *
     * @param projectId айди проекта
     * @param userId    айди пользователя
     * @return {@link ProjectSetting} дефолтные настройки проекта
     */
    ProjectSetting createDefaultProjectSettings(UUID projectId, UUID userId);

    /**
     * Сервисный метод для создания Outbox события
     *
     * @param projectId  айди проекта
     * @param projectKey ключ проекта
     * @param name имя проекта
     * @param createdBy айди юзера
     * @return {@link OutboxEvent} дефолтные настройки проекта
     */
    OutboxEvent createOutboxEvent(UUID projectId, String projectKey, String name, UUID createdBy);
}
