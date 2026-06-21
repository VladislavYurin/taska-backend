package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectMembershipInfoDto;
import ru.taska.domain.ProjectRole;

import java.util.UUID;

public interface ProjectMemberService {

    /**
     * Добавляет нового участника в проект.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param addedMemberId  айди добавляемого участника проекта
     * @param addingUserId айди добавляющего юзера
     * @param role  роль добавляемого пользователя в проекте
     * @param projectId айди проекта, в который добавляется новый пользователь
     *
     * @return Mono<{@link ProjectMember}> с соответствующими параметрами созданного проекта
     */
    Mono<ProjectMember> addProjectMember(String requestId, String nodeId, UUID addedMemberId, UUID addingUserId, ProjectRole role, UUID projectId);

    /**
     * Удаляет участника из проекта.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param deletedMemberId  айди добавляемого члена проекта
     * @param projectId айди проекта, в который добавляется новый пользователь
     *
     * @return Mono<{@link ProjectMember}> с данными удаленного участника
     */
    Mono<ProjectMember> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId, UUID projectId);

    /**
     * Изменяет роль участника проекта на заданную.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param changedMemberId  айди участника для изменения его роли
     * @param role  устанавливаемая роль участника в проекте
     * @param projectId айди проекта, в котором изменяется роль участника
     *
     * @return Mono<{@link ProjectMember}> с обновленной ролью
     */
    Mono<ProjectMember> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId, ProjectRole role, UUID projectId);

    /**
     * Проверяет роль участника проекта по полученным данным.
     *
     * @param requestId   уникальный идентификатор запроса.
     * @param nodeId      уникальный идентификатор узла.
     * @param projectId   уникальный идентификатор проекта.
     * @param userId    уникальный идентификатор участника проекта.
     *
     * @return ({@link ProjectMembershipInfoDto}) из трех элементов, содержащий:
     *              <ul>
     *              <li>{@code T1} ({@link ProjectRole}) — роль участника проекта</li>
     *              <li>{@code T2} ({@link Boolean}) — флаг, сигнализирующий о том, является ли пользователь участником проекта</li>
     *              <li>{@code T3} ({@link Boolean}) — флаг, сигнализирующий о том, существует ли такой проект</li>
     *              </ul>
     */
    Mono<ProjectMembershipInfoDto> checkProjectMemberRole(String requestId, String nodeId, UUID projectId, UUID userId);
}
