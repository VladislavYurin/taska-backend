package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.dto.ProjectAccessInfoDto;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

public interface ProjectMemberService {

    /**
     * Добавляет нового участника в проект.
     *
     * @param requestId     айди запроса
     * @param nodeId        айди узла
     * @param addedMemberId айди добавляемого участника проекта
     * @param actorUserId   айди инициатора события
     * @param role          роль добавляемого пользователя в проекте
     * @param projectId     айди проекта, в который добавляется новый пользователь
     * @return Mono<{@link ProjectMember}> с соответствующими параметрами созданного проекта
     */
    Mono<ProjectMember> addProjectMember(String requestId, String nodeId, UUID addedMemberId, UUID actorUserId, ProjectRole role, UUID projectId);

    /**
     * Удаляет участника из проекта.
     *
     * @param requestId       айди запроса
     * @param nodeId          айди узла
     * @param deletedMemberId айди добавляемого члена проекта
     * @param actorUserId     айди инициатора события
     * @param projectId       айди проекта, в который добавляется новый пользователь
     * @return Mono<{@link ProjectMember}> с данными удаленного участника
     */
    Mono<ProjectMember> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId, UUID actorUserId, UUID projectId);

    /**
     * Изменяет роль участника проекта на заданную.
     *
     * @param requestId       айди запроса
     * @param nodeId          айди узла
     * @param changedMemberId айди участника для изменения его роли
     * @param actorUserId     айди инициатора события
     * @param role            устанавливаемая роль участника в проекте
     * @param projectId       айди проекта, в котором изменяется роль участника
     * @return Mono<{@link ProjectMember}> с обновленной ролью
     */
    Mono<ProjectMember> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId, UUID actorUserId, ProjectRole role, UUID projectId);
}
