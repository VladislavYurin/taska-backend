package ru.taska.transport.grpc.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRoleChecker {

    private final GrpcProjectServiceClient client;
    private final IssueMapper issueMapper;

    /**
     * Проверяет, что пользователь является участником проекта и его роль входит в список допустимых.
     *
     * <p>Завершается пустым {@link Mono} при успешной проверке.
     * В случае ошибки бросает {@link DomainException}:</p>
     * <ul>
     *   <li>{@link DomainStatus#NOT_FOUND} — проект не найден;</li>
     *   <li>{@link DomainStatus#PERMISSION_DENIED} — пользователь не является участником проекта
     *       или его роль не входит в {@code allowedRoles}.</li>
     * </ul>
     */
    public Mono<Void> checkProjectRole(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId,
            Set<ProjectRole> allowedRoles
    ) {
        return client.checkProjectRole(requestId, nodeId, projectId, userId)
                .flatMap(response ->
                        validateAccess(requestId, nodeId, projectId, userId, allowedRoles, response)
                );
    }

    private Mono<Void> validateAccess(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId,
            Set<ProjectRole> allowedRoles,
            CheckProjectMemberRoleResponse response
    ) {
        if (!response.getProjectExists()) {
            log.warn("[{}][{}] Project doesn't exist: projectId={}",
                    requestId, nodeId, projectId
            );

            return Mono.error(new DomainException(
                    DomainStatus.NOT_FOUND, "Project not found")
            );
        }

        if (!response.getIsMember()) {
            log.warn("[{}][{}] User isn't a member of the project: projectId={}, userId={}",
                    requestId, nodeId, projectId, userId
            );

            return Mono.error(new DomainException(
                    DomainStatus.PERMISSION_DENIED, "Access denied")
            );
        }

        ProjectRole role = issueMapper.toDomainRole(response.getRole());
        if (!allowedRoles.contains(role)) {
            log.warn("[{}][{}] Not allowed role for the project: role={}, projectId={}, userId={}",
                    requestId, nodeId, role, projectId, userId
            );

            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Not allowed role"));
        }

        log.info("[{}][{}] Check role successfully complete: projectId={}, userId={}, role={}",
                requestId, nodeId, projectId, userId, role
        );

        return Mono.empty();
    }

}
