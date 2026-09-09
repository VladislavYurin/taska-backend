package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.AvatarDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.ProjectMemberDto;
import ru.taska.domain.dto.ProjectMembershipInfoDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.validator.ProjectMemberValidator;
import ru.taska.transport.grpc.client.GrpcAuthServiceClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final OutboxEventService outboxEventService;
    private final ProjectMemberValidator projectMemberValidator;
    private final GrpcAuthServiceClient authServiceClient;

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

    @Override
    public Flux<ProjectMemberDetailsDto> getProjectMembers(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId
    ) {
        return ensureProjectExists(requestId, nodeId, projectId)
                .thenMany(projectMemberRepository.findProjectMembers(projectId, actorUserId))
                .switchIfEmpty(
                        Mono.defer(() -> {
                            log.warn("[{}][{}] User {} has no access to project {}", requestId, nodeId, actorUserId, projectId);
                            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "User has no access to project"));
                        })
                )
                .buffer(100)
                .concatMap(batch -> enrichMembersBatch(batch, requestId, nodeId));
    }

    private Mono<Void> ensureProjectExists(String requestId, String nodeId, UUID projectId) {
        return projectRepository.existsById(projectId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(
                        Mono.defer(() -> {
                            log.warn("[{}][{}] Project not found: {}", requestId, nodeId, projectId);
                            return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found"));
                        })
                )
                .then();
    }

    private Flux<ProjectMemberDetailsDto> enrichMembersBatch(
            List<ProjectMemberDto> members,
            String requestId,
            String nodeId
    ) {
        List<UUID> userIds = members.stream()
                .map(ProjectMemberDto::userId)
                .distinct()
                .toList();

        return authServiceClient.getUserDetailsByIds(userIds, requestId, nodeId)
                .flatMapMany(response -> mergeMembersWithUsers(members, response.getUserDetailsList()));
    }

    private Flux<ProjectMemberDetailsDto> mergeMembersWithUsers(
            List<ProjectMemberDto> members,
            List<UserDetails> users
    ) {
        var usersById = users.stream()
                .collect(Collectors.toMap(
                        user -> UUID.fromString(user.getUserId()),
                        Function.identity()
                ));

        return Flux.fromIterable(members)
                .map(member -> {
                    var user = usersById.get(member.userId());
                    return buildProjectMemberDetailsDto(member, user);
                });
    }

    private ProjectMemberDetailsDto buildProjectMemberDetailsDto(ProjectMemberDto member, UserDetails user) {
        return ProjectMemberDetailsDto.builder()
                .userId(member.userId())
                .role(member.role())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatar(user.hasAvatar() ? toAvatarDto(user.getAvatar()) : null)
                .build();
    }

    private AvatarDto toAvatarDto(AvatarResponse avatarResponse) {
        return AvatarDto.builder()
                .id(UUID.fromString(avatarResponse.getId()))
                .userId(UUID.fromString(avatarResponse.getUserId()))
                .objectKey(avatarResponse.getObjectKey())
                .fileName(avatarResponse.getFileName())
                .contentType(avatarResponse.getContentType())
                .sizeBytes(avatarResponse.getSizeBytes())
                .downloadUrl(avatarResponse.getDownloadUrl())
                .build();
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
