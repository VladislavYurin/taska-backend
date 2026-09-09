package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectMemberDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.impl.ProjectMemberServiceImpl;
import ru.taska.service.impl.validator.ProjectMemberValidatorImpl;
import ru.taska.service.validator.ProjectMemberValidator;
import ru.taska.transport.grpc.client.GrpcAuthServiceClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceImplTest {

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GrpcAuthServiceClient grpcAuthServiceClient;

    @InjectMocks
    private ProjectMemberServiceImpl projectMemberService;

    private final String requestId = "req-123";
    private final String nodeId = "node-1";
    private final UUID projectMemberId = UUID.randomUUID();
    private final UUID memberAdminId = UUID.randomUUID();
    private final UUID memberViewerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private ProjectMemberDto mockMemberAdminDto;
    private ProjectMemberDto mockMemberViewerDto;
    private ProjectMember mockProjectMember;
    private UserDetails mockMemberViewerDetails;

    @BeforeEach
    void setUp() {
        mockProjectMember = ProjectMember.builder()
                .userId(projectMemberId)
                .projectId(projectId)
                .role(ProjectRole.ADMIN)
                .addedBy(actorId)
                .addedAt(Instant.now())
                .build();

        mockMemberAdminDto = ProjectMemberDto.builder()
                .userId(memberAdminId)
                .role(ProjectRole.ADMIN)
                .build();

        mockMemberViewerDto = ProjectMemberDto.builder()
                .userId(memberViewerId)
                .role(ProjectRole.VIEWER)
                .build();

        mockMemberViewerDetails = UserDetails.newBuilder()
                .setUserId(memberViewerId.toString())
                .setDisplayName("John Doe")
                .setEmail("john.doe@test.com")
                .build();

        ProjectMemberValidator projectMemberValidator = new ProjectMemberValidatorImpl(projectMemberRepository);

        projectMemberService = new ProjectMemberServiceImpl(
                projectMemberRepository,
                projectRepository,
                outboxEventService,
                projectMemberValidator,
                grpcAuthServiceClient
        );
    }

    @Test
    @DisplayName("Проверка успешного добавления участника проекта")
    void addProjectMember_Success() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));
        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(mockProjectMember));
        Mockito.when(outboxEventService.saveMemberAdded(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(ProjectMember.class)))
                .thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, projectMemberId, memberAdminId, ProjectRole.ADMIN, projectId))
                .expectNextMatches(pm ->
                        pm.getUserId().equals(projectMemberId) && pm.getProjectId().equals(projectId) && pm.getRole().equals(ProjectRole.ADMIN))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(projectMemberRepository).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(outboxEventService).saveMemberAdded(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если участник уже есть в проекте")
    void addProjectMember_ThrowsAlreadyExistsException_WhenMemberExists() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));

        StepVerifier.create(projectMemberService.addProjectMember(
                requestId, nodeId, memberViewerId, memberAdminId, ProjectRole.MEMBER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.ALREADY_EXISTS, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(projectMemberRepository, Mockito.never()).save(ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если актор не админ или не найден в проекте")
    void addProjectMember_ThrowsNotFoundException_WhenMemberIsNotAnAdminOrNotFound() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));

        StepVerifier.create(projectMemberService.addProjectMember(
                requestId, nodeId, memberViewerId, memberViewerId, ProjectRole.MEMBER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(projectMemberRepository, Mockito.never()).save(ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    @DisplayName("Проверка успешного удаления участника проекта")
    void rmProjectMember_Success() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));
        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberViewerId, projectId)).thenReturn(Mono.just(1L));
        Mockito.when(outboxEventService.saveMemberRemoved(requestId, nodeId, memberViewerId, projectId))
                .thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberViewerId, memberAdminId, projectId))
                .expectNextMatches(pm -> pm.getUserId().equals(memberViewerId) && pm.getProjectId().equals(projectId))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(projectMemberRepository).deleteByUserIdAndProjectId(memberViewerId, projectId);
        Mockito.verify(outboxEventService).saveMemberRemoved(requestId, nodeId, memberViewerId, projectId);
    }

    @Test
    @DisplayName("Проверка успешного удаления участника проекта")
    void rmProjectMember_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberViewerId, memberAdminId, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если актор не админ или не найден в проекте")
    void rmProjectMember_ThrowsNotFoundException_WhenActorIsNotAnAdminOrNotFound() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberAdminId, memberViewerId, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если участник последний админ в проекте")
    void rmProjectMember_ThrowsFailedPreconditionException_WhenMemberIsLastAdmin() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberAdminId, memberAdminId, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.FAILED_PRECONDITION, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(
        ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Проверка успешного изменения роли участника проекта")
    void changeProjectMemberRole_Success() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));
        Mockito.when(projectMemberRepository.updateRole(memberViewerId, ProjectRole.ADMIN, projectId))
                .thenReturn(Mono.just(1L));
        Mockito.when(outboxEventService.saveMemberUpdated(requestId, nodeId, memberViewerId, ProjectRole.ADMIN, projectId))
                .thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(
                requestId, nodeId, memberViewerId, memberAdminId, ProjectRole.ADMIN, projectId))
                .expectNextMatches(pm ->
                        pm.getUserId().equals(memberViewerId) && pm.getProjectId().equals(projectId) && pm.getRole().equals(ProjectRole.ADMIN))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(projectMemberRepository).updateRole(memberViewerId, ProjectRole.ADMIN, projectId);
        Mockito.verify(outboxEventService).saveMemberUpdated(requestId, nodeId, memberViewerId, ProjectRole.ADMIN, projectId);
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если актор не админ или не найден в проекте")
    void changeProjectMemberRole_ThrowsNotFoundException_WhenActorIsNotAnAdminOrNotFound() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberViewerId,
                        memberViewerId, ProjectRole.MEMBER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если участник последний админ в проекте")
    void checkProjectMemberRole_ThrowsFailedPreconditionException_WhenMemberIsLastAdmin() {
        Mockito.when(projectMemberRepository.getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberAdminId,
                        memberAdminId, ProjectRole.MEMBER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.FAILED_PRECONDITION, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectMemberRepository).getRequiredMembersInProject(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Проверка успешного получения списка пользователей проекта")
    void getProjectMembers_success() {
        UUID actorUserId = UUID.randomUUID();
        var response = GetUserDetailsByIdsResponse.newBuilder()
                .addUserDetails(mockMemberViewerDetails)
                .build();

        Mockito.when(projectRepository.existsById(projectId)).thenReturn(Mono.just(true));
        Mockito.when(projectMemberRepository.findProjectMembers(projectId, actorUserId))
                .thenReturn(Flux.just(mockMemberViewerDto));
        Mockito.when(grpcAuthServiceClient.getUserDetailsByIds(
                        List.of(memberViewerId),
                        requestId,
                        nodeId
                ))
                .thenReturn(Mono.just(response));

        StepVerifier.create(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId))
                .assertNext(result -> {
                    Assertions.assertEquals(memberViewerId, result.userId());
                    Assertions.assertEquals(mockMemberViewerDto.role(), result.role());
                    Assertions.assertEquals(mockMemberViewerDetails.getDisplayName(), result.displayName());
                    Assertions.assertEquals(mockMemberViewerDetails.getEmail(), result.email());
                    Assertions.assertNull(result.avatar());
                })
                .verifyComplete();

        Mockito.verify(projectRepository).existsById(projectId);
        Mockito.verify(projectMemberRepository).findProjectMembers(projectId, actorUserId);
        Mockito.verify(grpcAuthServiceClient).getUserDetailsByIds(List.of(memberViewerId), requestId, nodeId);
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если проект не найден")
    void getProjectMemberDetails_ThrowsNotFound_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.existsById(projectId))
                .thenReturn(Mono.just(false));

        StepVerifier.create(projectMemberService.getProjectMembers(requestId, nodeId, projectId,actorId))
                .expectErrorSatisfies(error -> {
                    Assertions.assertInstanceOf(DomainException.class, error);
                    var exception = (DomainException) error;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectRepository).existsById(projectId);
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если пользователь не участник проекта")
    void getProjectMemberDetails_ThrowsPermissionDenied_WhenUserIsNotProjectMember() {
        Mockito.when(projectRepository.existsById(projectId))
                .thenReturn(Mono.just(true));
        Mockito.when(projectMemberRepository.findProjectMembers(projectId, actorId))
                .thenReturn(Flux.empty());

        StepVerifier.create(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorId))
                .expectErrorSatisfies(error -> {
                    Assertions.assertInstanceOf(DomainException.class, error);
                    var exception = (DomainException) error;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectRepository).existsById(projectId);
        Mockito.verify(projectMemberRepository).findProjectMembers(projectId, actorId);
    }

    @Test
    @DisplayName("Проверка на выбрасывание ошибки, если информация о пользователе не найдена")
    void getProjectMembers_ThrowsException_WhenAuthServiceFailsToFindUser() {
        Mockito.when(projectRepository.existsById(projectId)).thenReturn(Mono.just(true));
        Mockito.when(projectMemberRepository.findProjectMembers(projectId, actorId))
                .thenReturn(Flux.just(mockMemberAdminDto, mockMemberViewerDto));

        var exception = new DomainException(DomainStatus.NOT_FOUND, "User not found");
        Mockito.when(grpcAuthServiceClient.getUserDetailsByIds(Mockito.anyList(), Mockito.eq(requestId), Mockito.eq(nodeId)))
                .thenReturn(Mono.error(exception));

        StepVerifier
                .create(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorId))
                .expectErrorSatisfies(error -> {
                    Assertions.assertInstanceOf(DomainException.class, error);
                    Assertions.assertEquals(
                            DomainStatus.NOT_FOUND,
                            ((DomainException) error).getStatus()
                    );
                })
                .verify();

        Mockito.verify(projectRepository).existsById(projectId);
        Mockito.verify(projectMemberRepository).findProjectMembers(projectId, actorId);
        Mockito.verify(grpcAuthServiceClient).getUserDetailsByIds(Mockito.anyList(), Mockito.eq(requestId), Mockito.eq(nodeId));
    }
}