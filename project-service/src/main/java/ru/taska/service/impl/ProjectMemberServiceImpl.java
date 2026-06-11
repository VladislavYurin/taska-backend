package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.entity.ProjectMember;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.ProjectMemberService;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMemberMapper projectMemberMapper;

    @Override
    @Transactional
    public Mono<AddProjectMemberResponse> addProjectMember(String requestId, String nodeId, UUID addedMemberId,
                                                           UUID addingUserId, ProjectRole role, UUID projectId) {
        return projectMemberRepository.existsByUserIdAndProjectId(addedMemberId, projectId)
                .flatMap( exists -> {
                    if (exists) {
                        log.info("[{}][{}] Project member with id {} already exists in project with id {}",
                                requestId, nodeId, addedMemberId, projectId);
                        return Mono.<ProjectMember>error(new DomainException(DomainStatus.ALREADY_EXISTS,
                                "Project member with id " + addedMemberId + " already exists in project with id " + projectId));
                    }
                            ProjectMember addedMember = ProjectMember.builder()
                                    .userId(addedMemberId)
                                    .projectId(projectId)
                                    .role(projectMemberMapper.toProjectRole(role))
                                    .addedAt(Instant.now())
                                    .addedBy(addingUserId)
                                    .build();

                            return projectMemberRepository.save(addedMember);
                        })
                .map(projectMemberMapper::toAddProjectMemberResponse)
                .doOnSuccess(pm ->
                        log.info("[{}][{}] Project member with id {} successfully added to project with id {}",
                                requestId, nodeId, addedMemberId, projectId));
    }

    @Override
    @Transactional
    public Mono<RmProjectMemberResponse> rmProjectMember(String requestId, String nodeId, UUID deletedMemberId, UUID projectId) {
        return projectMemberRepository.deleteByUserIdAndProjectId(deletedMemberId, projectId)
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        log.info("[{}][{}] Project member with id {} was not found in project with id {}",
                                requestId, nodeId, deletedMemberId, projectId);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project member with id " + deletedMemberId +
                                " was not found in project with id " + projectId));
                    }
                    return Mono.just(RmProjectMemberResponse.newBuilder()
                            .setDeletedMemberId(String.valueOf(deletedMemberId))
                            .setProjectId(String.valueOf(projectId))
                            .build());
                })
                .doOnSuccess(deletedMember ->
                        log.info("[{}][{}] Project member with id {} successfully deleted from project with id {}",
                                requestId, nodeId, deletedMemberId, projectId));
    }

    @Override
    @Transactional
    public Mono<ChangeRoleResponse> changeProjectMemberRole(String requestId, String nodeId, UUID changedMemberId, ProjectRole role, UUID projectId) {
        return projectMemberRepository.updateRole(changedMemberId, projectMemberMapper.toProjectRole(role), projectId)
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        log.info("[{}][{}] Project member with id {} was not found in project with id {}",
                                requestId, nodeId, changedMemberId, projectId);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project member with id " + changedMemberId +
                                " was not found in project with id " + projectId));
                    }
                    return Mono.just(ChangeRoleResponse.newBuilder()
                            .setChangedMemberId(String.valueOf(changedMemberId))
                            .setRole(role)
                            .setProjectId(String.valueOf(projectId))
                            .build());
                })
                .doOnSuccess(changedMemberResponse ->
                        log.info("[{}][{}] Project members role with id {} successfully updated for {} in project with id {}",
                                requestId, nodeId, changedMemberId, role, projectId));
    }
}
