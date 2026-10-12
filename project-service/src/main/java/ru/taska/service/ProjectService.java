package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.domain.dto.projectContext.ProjectContextDto;

import java.util.List;
import java.util.Map;
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
     * @return Mono<{@link Project}> с данными созданного проекта
     */
    Mono<Project> createProject(
            String requestId,
            String nodeId,
            String projectKey,
            String projectName,
            UUID userId
    );

    /**
     * Возвращает дто проекта из БД по Id
     *
     * @param projectId   айди проекта
     * @param actorUserId aйди актора, для проверки доступа (вхождения) в проект
     * @return Mono<{@link ProjectCheckMembershipDto}> представление данных проекта с id и role юзера
     */
    Mono<ProjectCheckMembershipDto> getProject(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId
    );

    /**
     * Возвращает дто контекста проекта из project-db, а так же с данными из issue и workflow сервисов
     *
     * @param projectId   айди проекта
     * @param actorUserId aйди актора, для проверки доступа (вхождения) в проект
     * @return Mono<{@link ProjectContextDto}> контекст проекта
     */
    Mono<ProjectContextDto> getProjectContext(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            GlobalRole globalRole
    );

    /**
     * Возвращает список всех проектов пользователя по его Id
     *
     * @param userId айди пользователя
     * @return Flux<{@link Project}> список проектов пользователя с перeданным в метод Id
     */
    Flux<ProjectCheckMembershipDto> listMyProjects(
            String requestId,
            String nodeId,
            UUID userId
    );

    /**
     * Возвращает ключ проекта по Id (без проверки прав).
     * Используется ТОЛЬКО для внутренних вызовов из других сервисов, которые уже проверили права доступа.
     * @param projectId айди проекта
     * @return Mono с ключом проекта
     */
    Mono<String> getProjectKeyByIdInternal(UUID projectId);

    Mono<Map<UUID, ProjectInfo>> getProjectInfoByIds(List<UUID> projectIds);
}
