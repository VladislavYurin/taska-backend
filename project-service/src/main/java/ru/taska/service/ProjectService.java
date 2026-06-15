package ru.taska.service;

import reactor.core.publisher.Flux;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.UsersProjectsResponse;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectSetting;

import java.util.UUID;
import ru.taska.domain.ProjectSetting;

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
    Mono<Project> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId);

    /**
     * Возвращает проект из БД на основе {@link ru.taska.api.project.v1.GetProjectRequest}
     * Получает projectId для поиска требуемого проекта в БД
     *
     * @param projectId айди проекта
     * @return Mono<{@link ProjectResponse}> проект из БД по запросу
     */
    Mono<Project> getProject(String requestId, String nodeId, UUID projectId);

    /**
     * Возвращает список всех проектов пользователя на основе {@link ru.taska.api.project.v1.GetProjectRequest}
     * Получает userId для поиска всех проектов текущего пользователя в БД
     *
     * @param userId айди пользователя
     * @return Mono<{@link UsersProjectsResponse}> список проектов текущего пользователя
     */
    Flux<Project> listMyProjects(String requestId, String nodeId, UUID userId);

}
