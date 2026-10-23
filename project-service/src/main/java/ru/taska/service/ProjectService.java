package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectUpdateResult;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.domain.dto.projectContext.ProjectContextDto;

import java.util.List;
import java.util.Map;
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

    /**
     * Обновляет проект - имя, описание, цвет. Обновляются только переданные (непустые Optional) поля,
     * отсутствующие поля остаются без изменений. Флаги clearDescription/clearColor позволяют явно
     * сбросить description/color в null (отличается от "поле не передано").
     *
     * @param requestId - айди запроса.
     * @param nodeId - айди узла.
     * @param projectId - айди проекта.
     * @param actorUserId - айди пользователя, выполняющего изменение проекта (должен быть ADMIN проекта).
     * @param name - новое имя проекта, если передано.
     * @param description - новое описание проекта, если передано.
     * @param clearDescription - если true, описание явно сбрасывается в null (приоритетнее description).
     * @param color - новый цвет проекта (HEX), если передан.
     * @param clearColor - если true, цвет явно сбрасывается в null (приоритетнее color).
     * @return Mono<{@link ProjectUpdateResult}> с обновлёнными данными проекта и ролью актора
     *         (ADMIN — её проверяет этот же метод), чтобы ответ мог содержать currentUserRole.
     */
    Mono<ProjectUpdateResult> updateProject(String requestId, String nodeId, UUID projectId, UUID actorUserId, Optional<String> name,
                                 Optional<String> description, boolean clearDescription,
                                 Optional<String> color, boolean clearColor);
}
