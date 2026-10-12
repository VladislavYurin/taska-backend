package ru.taska.service.impl.validator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectMemberDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.validator.ProjectMemberValidator;
import ru.taska.transport.grpc.client.GrpcAuthServiceClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberValidatorImpl implements ProjectMemberValidator {

    private final ProjectMemberRepository projectMemberRepository;
    private final GrpcAuthServiceClient authServiceClient;

    public Mono<Boolean> validateBeforeAdd(String requestId, String nodeId, UUID actorUserId, UUID addedMemberId, UUID projectId) {
        return getProjectMembers(requestId, nodeId, actorUserId, addedMemberId, projectId)
                .flatMap(members -> validateActorIsAdmin(requestId, nodeId, actorUserId, projectId, members))
                .flatMap(members -> validateUserNotMember(members, addedMemberId, projectId, requestId, nodeId)) 
                .flatMap(members -> validateUserExists( addedMemberId, requestId, nodeId ))
                .thenReturn(true);
    }

    private Mono<Void> validateUserExists(UUID addedMemberId, String requestId, String nodeId) {
        return authServiceClient.getUserDetailsByIds(List.of(addedMemberId), requestId, nodeId)
                .filter(response -> response.getUserDetailsCount() > 0)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}] User {} doesn't exist", requestId, nodeId, addedMemberId);
                    return Mono.error(new DomainException( DomainStatus.INVALID_ARGUMENT, "User: " + addedMemberId + " doesn't exist" ));
                }))
                .then();
    }

    private Mono<Map<UUID, ProjectRole>> validateUserNotMember(Map<UUID, ProjectRole> members, UUID addedMemberId,
                                                               UUID projectId, String requestId, String nodeId) {
        if (!members.containsKey(addedMemberId)) { 
            return Mono.just(members); 
        } 
        log.info("[{}][{}] User with id: {} already exists in project: {}", requestId, nodeId, addedMemberId, projectId); 
        return Mono.error(new DomainException( DomainStatus.ALREADY_EXISTS,
                "User with id: " + addedMemberId + " already exists in project: " + projectId )
        );
    }

    private Mono<Map<UUID, ProjectRole>> validateActorIsAdmin(String requestId, String nodeId, UUID actorUserId,
                                                              UUID projectId, Map<UUID, ProjectRole> members) {
        if (members.get(actorUserId) == ProjectRole.ADMIN) {
            return Mono.just(members);
        }
        log.info("[{}][{}] Actor {} is not an admin or doesn't exist in project {}", requestId, nodeId, actorUserId, projectId);
        return Mono.error(
                new DomainException(DomainStatus.PERMISSION_DENIED,
                        "Actor " + actorUserId + " is not an admin of project: " + projectId)
        );
    }

    private Mono<Map<UUID, ProjectRole>> getProjectMembers(String requestId, String nodeId, UUID actorUserId, UUID addedMemberId, UUID projectId) {
        return projectMemberRepository.getRequiredMembersInProjectNoLock(actorUserId, addedMemberId, projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}] Project {} doesn't exist", requestId, nodeId, projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project: " + projectId + " doesn't exist"));
                }))
                .collectMap(ProjectMemberDto::userId, ProjectMemberDto::role);
    }

    public Mono<Boolean> validateBeforeModify(String requestId, String nodeId, UUID actorUserId, UUID changedMemberId, UUID projectId) {
        return projectMemberRepository.getRequiredMembersInProject(actorUserId, changedMemberId, projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}] Project {} doesn't exist", requestId, nodeId, projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project: " + projectId + " doesn't exist"));
                }))
                .collectMap(ProjectMemberDto::userId, ProjectMemberDto::role)
                .flatMap(members -> {
                    //Существует ли изменяемый в проекте?
                    if (!members.containsKey(changedMemberId)) {
                        log.info("[{}][{}] Project member with id {} was not found in project with id {}",
                                requestId, nodeId, changedMemberId, projectId);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                                "Project member with id " + changedMemberId + " was not found in project with id " + projectId));
                    }
                    //Является ли актор админом?
                    if (members.get(actorUserId) != ProjectRole.ADMIN) {
                        log.info("[{}][{}] Actor {} is not an admin or doesn't exist in project {}",
                                requestId, nodeId, actorUserId, projectId);
                        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                                "User with id: " + actorUserId + " is not an admin of project: " + projectId));
                    }
                    //Не является ли изменяемый последним админом?
                    if (members.get(changedMemberId) == ProjectRole.ADMIN) {
                        long adminCount = members.values().stream()
                                .filter(role -> role == ProjectRole.ADMIN)
                                .count();
                        if (adminCount <= 1) {
                            log.info("[{}][{}] Try to modify last admin: {} in project: {}",
                                    requestId, nodeId, changedMemberId, projectId);
                            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                    "Can't modify last admin: " + changedMemberId + " in project: " + projectId));
                        }
                    }
                    return Mono.just(true);
                });
    }
}
