package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;

import java.util.UUID;

public interface ProjectMemberService {

    /**
     * Добавляет нового участника проекта {@link ru.taska.api.project.v1.AddProjectMemberRequest} в проект
     * Возвращает {@link AddProjectMemberResponse} с айди пользователя и проекта и установленной ролью
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param addedMemberId  айди добавляемого участника проекта
     * @param addingUserId айди добавляющего юзера
     * @param role  роль добавляемого пользователя в проекте
     * @param projectId айди проекта, в который добавляется новый пользователь
     *
     * @return Mono<{@link AddProjectMemberResponse}> с соответствующими параметрами созданного проекта
     */
    Mono<AddProjectMemberResponse> addProjectMember(String requestId, String nodeId, UUID addedMemberId, UUID addingUserId, ProjectRole role, UUID projectId);

    /**
     * Удаляет участника из проекта по запросу {@link ru.taska.api.project.v1.RmProjectMemberRequest}
     * Возвращает {@link AddProjectMemberResponse} с айди пользователя и проекта
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param deletedMemberId  айди добавляемого члена проекта
     * @param projectId айди проекта, в который добавляется новый пользователь
     *
     * @return Mono<{@link RmProjectMemberResponse}> с айди проекта и удаленного участника
     */
    Mono<RmProjectMemberResponse> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId, UUID projectId);

    /**
     * Изменяет роль участника проекта на заданную {@link ru.taska.api.project.v1.ChangeRoleRequest}
     * Возвращает {@link ChangeRoleResponse} с айди пользователя и проекта и установленной ролью
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param changedMemberId  айди участника для изменения его роли
     * @param role  устанавливаемая роль участника в проекте
     * @param projectId айди проекта, в котором изменяется роль участника
     *
     * @return Mono<{@link ChangeRoleResponse}> с айди проекта и удаленного участника и установленной ролью
     */
    Mono<ChangeRoleResponse> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId, ProjectRole role, UUID projectId);

    /**
     * Проверяет роль участника проекта на основе {@link ru.taska.api.project.v1.CheckProjectMemberRoleRequest}
     * Получает данные из header (requestId, nodeId) для логирования,
     * а так же projectId и memberId для получения роли пользователя проекта.
     *
     * @param requestId   уникальный идентификатор запроса.
     * @param nodeId      уникальный идентификатор узла.
     * @param projectId   уникальный идентификатор проекта.
     * @param memberId    уникальный идентификатор участника проекта.
     *
     * @return Mono<{@link CheckProjectMemberRoleResponse}> с ролью участника, а так же boolean полями
     * является ли пользователь участником проекта и существует ли такой проект.
     */
    Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole(String requestId, String nodeId, UUID projectId, UUID memberId);
}
