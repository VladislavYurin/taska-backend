package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRoleChecker {

    private final GrpcProjectServiceClient client;

    public Mono<Void> checkProjectRole(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId,
            Set<ProjectRole> allowedRoles
    ) {
        return client.checkProjectRole(requestId, nodeId, projectId, userId)
                .flatMap(response ->
                        validateAccess(projectId, userId, allowedRoles, response)
                );
    }

    private Mono<Void> validateAccess(
            UUID projectId,
            UUID userId,
            Set<ProjectRole> allowedRoles,
            CheckProjectMemberRoleResponse response
    ) {
        if (!response.getProjectExists()) {
            log.warn("Project doesn't exist: projectId={}", projectId);

            return Mono.error(new DomainException(
                    DomainStatus.NOT_FOUND, "Project not found")
            );
        }

        if (!response.getIsMember()) {
            log.warn("User isn't a member of the project: projectId={}, userId={}",
                    projectId, userId
            );

            return Mono.error(new DomainException(
                    DomainStatus.PERMISSION_DENIED, "Access denied")
            );
        }

        ProjectRole role = response.getRole();
        if (!allowedRoles.contains(role)) {
            log.warn("Not allowed role for the project: role={}, projectId={}, userId={}",
                    role, projectId, userId
            );

            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Not allowed role"));
        }

        log.info("Check role successfully complete: projectId={}, userId={}, role={}",
                projectId, userId, role
        );

        return Mono.empty();
    }

}
