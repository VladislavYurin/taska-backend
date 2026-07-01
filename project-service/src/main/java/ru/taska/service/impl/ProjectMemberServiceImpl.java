package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectMembershipInfoDto;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.validator.ProjectMemberValidator;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final OutboxEventService outboxEventService;
    private final ProjectMemberValidator projectMemberValidator;

    @Override
    @Transactional
    public Mono<ProjectMember> addProjectMember(String requestId, String nodeId, UUID addedMemberId,
                                                UUID actorUserId, ProjectRole role, UUID projectId) {
        return projectMemberValidator.validateBeforeAdd(requestId, nodeId, actorUserId, addedMemberId, projectId)
                .flatMap(b -> {
                    ProjectMember addedMember = ProjectMember.builder()
                            .userId(addedMemberId)
                            .projectId(projectId)
                            .role(role)
                            .addedAt(Instant.now())
                            .addedBy(actorUserId)
                            .build();

                    return projectMemberRepository.save(addedMember)
                            .then(outboxEventService.saveMemberAdded(requestId, nodeId, addedMember))
                            .thenReturn(addedMember);
                })
                .doOnSuccess(pm ->
                        log.info("[{}][{}] Project member with id {} successfully added to project with id {}",
                                requestId, nodeId, addedMemberId, projectId));
    }

    @Override
    @Transactional
    public Mono<ProjectMember> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId,
                                               UUID actorUserId, UUID projectId) {
        return projectMemberValidator.validateBeforeModify(requestId, nodeId, actorUserId, deletedMemberId, projectId)
                .flatMap( b -> {
                        ProjectMember deletedMember = ProjectMember.builder()
                                .userId(deletedMemberId)
                                .projectId(projectId)
                                .build();

                        return projectMemberRepository.deleteByUserIdAndProjectId(deletedMemberId, projectId)
                                .then(outboxEventService.saveMemberRemoved(requestId, nodeId, deletedMemberId, projectId))
                                .thenReturn(deletedMember);
                    })
                .doOnSuccess(deletedMember ->
                            log.info("[{}][{}] Project member with id {} successfully deleted from project with id {}",
                                    requestId, nodeId, deletedMemberId, projectId));
        }

    @Override
    @Transactional()
    public Mono<ProjectMember> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId,
                                                       UUID actorUserId, ProjectRole role, UUID projectId) {
    return projectMemberValidator.validateBeforeModify(requestId, nodeId, actorUserId, changedMemberId, projectId)
            .flatMap(b -> {
                ProjectMember changedMember = ProjectMember.builder()
                        .userId(changedMemberId)
                        .role(role)
                        .projectId(projectId)
                        .build();
                return projectMemberRepository.updateRole(changedMemberId, role, projectId)
                        .then(outboxEventService.saveMemberUpdated(requestId, nodeId, changedMemberId, role, projectId))
                        .thenReturn(changedMember);
            })
            .doOnSuccess(changedMemberResponse ->
                    log.info("[{}][{}] Role of project member: {} successfully changed for: {} in project: {}",
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

    private ProjectMembershipInfoDto createProjectMembershipInfoDto(ProjectRole role, Boolean isMember, Boolean
            isProjectExists) {
        return ProjectMembershipInfoDto.builder()
                .role(role)
                .isMember(isMember)
                .isProjectExists(isProjectExists)
                .build();
    }

}
