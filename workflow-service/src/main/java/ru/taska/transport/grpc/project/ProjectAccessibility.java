package ru.taska.transport.grpc.project;

import io.grpc.StatusRuntimeException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.RoleMapper;
import ru.taska.mapper.WorkflowMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectAccessibility {
    private final GrpcProjectServiceClient client;
    private final RoleMapper roleMapper;

    /**
     * Проверяет, что проект не удален, а пользователь является участником проекта и его роль входит в список допустимых.
     *
     * <p>Возвращает {@link ProjectResponse} при успешной проверке.
     * В случае ошибки бросает {@link DomainException}:</p>
     * <ul>
     *   <li>{@link DomainStatus#NOT_FOUND} — проект не найден;</li>
     *   <li>{@link DomainStatus#PERMISSION_DENIED} — пользователь не является участником проекта
     *       или его роль не входит в {@code allowedRoles}.</li>
     *   <li>{@link DomainStatus#PROJECT_ARCHIVED} — проект удален (soft delete);</li>
     * </ul>
     */
    public Mono<ProjectResponse> check(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            Set<ProjectRole> allowedRoles
    ) {
        return client.getProject(requestId, nodeId, projectId, actorUserId)
                     .onErrorMap(
                             StatusRuntimeException.class, e ->
                                     mapGrpcError(requestId, nodeId, projectId, actorUserId, e)
                     )
                     .flatMap(projectResponse -> {
                         return validateAccess(requestId, nodeId, projectResponse, allowedRoles).thenReturn(projectResponse);
                     });
    }

    private Mono<Void> validateAccess(
            String requestId,
            String nodeId,
            ProjectResponse projectResponse,
            Set<ProjectRole> allowedRoles
    ) {
        ProjectRole role = roleMapper.toDomainRole(projectResponse.getCurrentUserRole());
        if (!allowedRoles.contains(role)) {
            log.warn(
                    "[{}][{}] Not allowed role for the project: role={}, projectId={}",
                    requestId, nodeId, role, projectResponse.getId()
            );

            return Mono.error(new DomainException(
                    DomainStatus.PERMISSION_DENIED,
                    "Not allowed role"
            ));
        }
        if (projectResponse.hasArchivedAt()) {
            log.warn("[{}][{}] Project archived: projectId={} at {}",
                     requestId, nodeId, projectResponse.getId(), projectResponse.getArchivedAt()
            );

            return Mono.error(new DomainException(
                    DomainStatus.PROJECT_ARCHIVED, "Project archived")
            );
        }
        return Mono.empty();
    }

    private Throwable mapGrpcError(String requestId, String nodeId, UUID projectId, UUID actorUserId, StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> {
                log.warn("[{}][{}] Project not found: {}", requestId, nodeId, projectId);
                yield new DomainException(DomainStatus.NOT_FOUND, "Project not found");
            }
            case PERMISSION_DENIED -> {
                log.warn("[{}][{}] User {} is not a member of project {}", requestId, nodeId, actorUserId, projectId);
                yield new DomainException(DomainStatus.PERMISSION_DENIED, "You don't have access to this project");
            }
            case UNAVAILABLE, DEADLINE_EXCEEDED -> {
                log.error("[{}][{}] Project service unavailable: projectId={}, code={}",
                          requestId, nodeId, projectId, e.getStatus().getCode(), e);
                yield new DomainException(DomainStatus.UNAVAILABLE, "Project service unavailable");
            }
            default -> {
                log.error("[{}][{}] Unexpected gRPC error from project service: projectId={}, code={}",
                          requestId, nodeId, projectId, e.getStatus().getCode(), e);
                yield e;
            }
        };
    }
}
