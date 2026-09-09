package ru.taska.transport.grpc.project;

import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.CheckProjectAccessResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.RoleMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectAccessChecker {

    private final GrpcProjectServiceClient client;
    private final RoleMapper roleMapper;

    public Mono<Void> checkProjectAccess(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId,
            Set<ProjectRole> allowedRoles
    ) {
        return client.checkProjectAccess(requestId, nodeId, projectId, userId)
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
            CheckProjectAccessResponse response
    ) {
        if (!response.getProjectExists()) {
            log.warn("[{}][{}] Project doesn't exist: projectId={}",
                    requestId, nodeId, projectId
            );
            return Mono.error(new DomainException(
                    DomainStatus.NOT_FOUND, "Project not found")
            );
        }

        if (response.getProjectArchived()) {
            log.warn("[{}][{}] Project archived: projectId={}",
                     requestId, nodeId, projectId
            );

            return Mono.error(new DomainException(
                    DomainStatus.PROJECT_ARCHIVED, "Project archived")
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

        ProjectRole role = roleMapper.toDomainRole(response.getRole());
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
