package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;

import java.util.UUID;
import ru.taska.domain.ProjectRole;

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
    Mono<ProjectCheckMembershipDto> getProject(String requestId, String nodeId, UUID projectId, UUID actorUserId);

    /**
     * Возвращает список всех проектов пользователя по его Id
     *
     * @param userId айди пользователя
     * @return Flux<{@link Project}> список проектов пользователя с перeданным в метод Id
     */
    Flux<ProjectCheckMembershipDto> listMyProjects(String requestId, String nodeId, UUID userId);

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
}
