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

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberValidatorImpl implements ProjectMemberValidator {

    private final ProjectMemberRepository projectMemberRepository;

    public Mono<Boolean> validateBeforeAdd(String requestId, String nodeId, UUID actorUserId, UUID addedMemberId, UUID projectId) {
        return projectMemberRepository.getRequiredMembersInProject(actorUserId, addedMemberId, projectId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}] Project {} doesn't exist", requestId, nodeId, projectId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project: " + projectId + " doesn't exist"));
                }))
                .collectMap(ProjectMemberDto::userId, ProjectMemberDto::role)
                .flatMap(members -> {
                    //Есть ли актор на проекте и является ли он админом?
                    if (!members.containsKey(actorUserId) || members.get(actorUserId) != ProjectRole.ADMIN) {
                        log.info("[{}][{}] Actor {} is not an admin or doesn't exist in project {}",
                                requestId, nodeId, actorUserId, projectId);
                        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Actor " + actorUserId +
                                " is not an admin of project: " + projectId));
                    }
                    //Есть ли добавляемый уже в проекте?
                    if (members.containsKey(addedMemberId)) {
                        log.info("[{}][{}] User with id: {} already exists in project: {}",
                                requestId, nodeId, addedMemberId, projectId);
                        return Mono.error(new DomainException(DomainStatus.ALREADY_EXISTS, "User with id: " + addedMemberId +
                                " already exists in project: " + projectId));
                    }
                    return Mono.just(true);
                });
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
