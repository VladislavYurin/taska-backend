package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;

import java.util.Optional;
import java.util.UUID;

public interface ProjectService {

    /**
     * Создает в БД новый проект с переданными параметрами.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param projectKey  ключ проекта
     * @param projectName имя проекта
     * @param userId      айди юзера, создающего проект
     * @param description описание проекта, если передано
     * @param color       цвет проекта (HEX), если передан
     * @return Mono<{@link Project}> с данными созданного проекта
     */
    Mono<Project> createProject(String requestId, String nodeId, String projectKey, String projectName, UUID userId,
                                 Optional<String> description, Optional<String> color);

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
     * Возвращает ключ проекта по Id (без проверки прав).
     * Используется ТОЛЬКО для внутренних вызовов из других сервисов, которые уже проверили права доступа.
     * @param projectId айди проекта
     * @return Mono с ключом проекта
     */
    Mono<String> getProjectKeyByIdInternal(UUID projectId);

    /**
     * Обновляет проект - имя, описание, цвет. Обновляются только переданные (непустые Optional) поля,
     * отсутствующие поля остаются без изменений.
     *
     * @param requestId - айди запроса.
     * @param nodeId - айди узла.
     * @param projectId - айди проекта.
     * @param actorUserId - айди пользователя, выполняющего изменение проекта (должен быть ADMIN проекта).
     * @param name - новое имя проекта, если передано.
     * @param description - новое описание проекта, если передано.
     * @param color - новый цвет проекта (HEX), если передан.
     * @return Mono<{@link Project}> с обновлёнными данными проекта.
     */
    Mono<Project> updateProject(String requestId, String nodeId, UUID projectId, UUID actorUserId, Optional<String> name,
                                 Optional<String> description, Optional<String> color);

}
