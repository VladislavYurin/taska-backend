package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectMembershipInfoDto;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.ProjectMemberService;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional
    public Mono<ProjectMember> addProjectMember(String requestId, String nodeId, UUID addedMemberId,
                                                UUID addingUserId, ProjectRole role, UUID projectId) {
        return projectMemberRepository.existsByUserIdAndProjectId(addedMemberId, projectId)
                .flatMap(exists -> {
                    if (exists) {
                        log.info("[{}][{}] Project member with id {} already exists in project with id {}",
                                requestId, nodeId, addedMemberId, projectId);
                        return Mono.<ProjectMember>error(new DomainException(DomainStatus.ALREADY_EXISTS,
                                "Project member with id " + addedMemberId + " already exists in project with id " + projectId));
                    }
                    ProjectMember addedMember = ProjectMember.builder()
                            .userId(addedMemberId)
                            .projectId(projectId)
                            .role(role)
                            .addedAt(Instant.now())
                            .addedBy(addingUserId)
                            .build();

                    return projectMemberRepository.save(addedMember)
                            .then(outboxEventService.saveMemberAdded(requestId, addedMember))
                            .thenReturn(addedMember);
                })
                .doOnSuccess(pm ->
                        log.info("[{}][{}] Project member with id {} successfully added to project with id {}",
                                requestId, nodeId, addedMemberId, projectId));
    }

    @Override
    @Transactional
    public Mono<ProjectMember> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId, UUID projectId) {
        return projectMemberRepository.deleteByUserIdAndProjectId(deletedMemberId, projectId)
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        log.info("[{}][{}] Project member with id {} was not found in project with id {}",
                                requestId, nodeId, deletedMemberId, projectId);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project member with id " + deletedMemberId +
                                " was not found in project with id " + projectId));
                    }

                    ProjectMember deletedMember = ProjectMember.builder()
                            .userId(deletedMemberId)
                            .projectId(projectId)
                            .build();

                    return outboxEventService.saveMemberRemoved(requestId, deletedMemberId, projectId)
                            .thenReturn(deletedMember);
                })
                .doOnSuccess(deletedMember ->
                        log.info("[{}][{}] Project member with id {} successfully deleted from project with id {}",
                                requestId, nodeId, deletedMemberId, projectId));
    }

    @Override
    @Transactional
    public Mono<ProjectMember> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId, ProjectRole role, UUID projectId) {
        return projectMemberRepository.updateRole(changedMemberId, role, projectId)
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        log.info("[{}][{}] Project member with id {} was not found in project with id {}",
                                requestId, nodeId, changedMemberId, projectId);
                        return Mono.<ProjectMember>error(new DomainException(DomainStatus.NOT_FOUND, "Project member with id " + changedMemberId +
                                " was not found in project with id " + projectId));
                    }
                    return Mono.just(ProjectMember.builder()
                            .userId(changedMemberId)
                            .role(role)
                            .projectId(projectId)
                            .build());
                })
                .doOnSuccess(changedMemberResponse ->
                        log.info("[{}][{}] Project members role with id {} successfully updated for {} in project with id {}",
                                requestId, nodeId, changedMemberId, role, projectId));
    }

    @Override
    public Mono<ProjectMembershipInfoDto> checkProjectMemberRole(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID userId
    ) {
        return projectRepository.findById(projectId)
                .flatMap(project -> projectMemberRepository.findByUserIdAndProjectId(userId, projectId)
                        .map(pm -> this.createProjectMembershipInfoDto(pm.getRole(), true, true))
                        .switchIfEmpty(Mono.defer(() -> Mono.just(
                                this.createProjectMembershipInfoDto(ProjectRole.UNSPECIFIED, false, true)
                        )))
                )
                .switchIfEmpty(Mono.defer(() -> Mono.just(
                        this.createProjectMembershipInfoDto(ProjectRole.UNSPECIFIED, false, false)
                )))
                .doOnSuccess(t -> {
                    if (t != null) {
                        log.info("[{}][{}] Checking project role completed: " +
                                        "projectId={}, userId={}, role={}, isMember={}, projectExists={}",
                                requestId, nodeId, projectId, userId,
                                t.role(), t.isMember(), t.isProjectExists()
                        );
                    }
                });
    }

    private ProjectMembershipInfoDto createProjectMembershipInfoDto(ProjectRole role, Boolean isMember, Boolean isProjectExists) {
        return ProjectMembershipInfoDto.builder()
                .role(role)
                .isMember(isMember)
                .isProjectExists(isProjectExists)
                .build();
    }

}
