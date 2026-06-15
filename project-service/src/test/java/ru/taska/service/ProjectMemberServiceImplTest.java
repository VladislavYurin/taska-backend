package ru.taska.service;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeRoleResponse;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.impl.ProjectMemberServiceImpl;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceImplTest {

    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private OutboxEventService outboxEventService;

    @InjectMocks private ProjectMemberServiceImpl projectMemberService;

    private final String requestId = "req-123";
    private final String nodeId = "node-1";
    private final UUID memberId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID initiatorId = UUID.randomUUID();

    private ProjectMember mockMember;

    @BeforeEach
    void setUp() {
        mockMember = ProjectMember.builder()
                .userId(memberId)
                .projectId(projectId)
                .role(ProjectRole.MEMBER)
                .build();
    }

    @Test
    void addProjectMember_Success() {
        AddProjectMemberResponse mockResponse = AddProjectMemberResponse.newBuilder()
                .setAddedMemberId(memberId.toString())
                .setProjectId(projectId.toString())
                .setRole(ru.taska.api.project.v1.ProjectRole.MEMBER)
                .build();

        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(false));
        Mockito.when(projectMemberMapper.toProjectRole(ru.taska.api.project.v1.ProjectRole.MEMBER)).thenReturn(ProjectRole.MEMBER);
        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(mockMember));
        Mockito.when(projectMemberMapper.toAddProjectMemberResponse(ArgumentMatchers.any(ProjectMember.class))).thenReturn(mockResponse);
        Mockito.when(outboxEventService.saveMemberAdded(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, memberId, initiatorId, ru.taska.api.project.v1.ProjectRole.MEMBER, projectId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectMemberRepository).existsByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(projectMemberRepository).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(outboxEventService).saveMemberAdded(ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    void addProjectMember_ThrowsAlreadyExistsException_WhenMemberExists() {
        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(true));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, memberId, initiatorId, ru.taska.api.project.v1.ProjectRole.MEMBER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.ALREADY_EXISTS, exception.getStatus());
                    Assertions.assertEquals("Project member with id " + memberId + " already exists in project with id " + projectId, exception.getMessage());
                })
                .verify();

        Mockito.verify(projectMemberRepository).existsByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(projectMemberRepository, Mockito.never()).save(ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    void rmProjectMember_Success() {
        RmProjectMemberResponse mockResponse = RmProjectMemberResponse.newBuilder()
                .setDeletedMemberId(memberId.toString())
                .setProjectId(projectId.toString())
                .build();

        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(1L));
        Mockito.when(outboxEventService.saveMemberRemoved(Mockito.any(), Mockito.any())).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberId, projectId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectMemberRepository).deleteByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(outboxEventService).saveMemberRemoved(Mockito.any(), Mockito.any());
    }

    @Test
    void rmProjectMember_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(0L));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberId, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project member with id " + memberId + " was not found in project with id " + projectId, exception.getMessage());
                })
                .verify();

        Mockito.verify(projectMemberRepository).deleteByUserIdAndProjectId(memberId, projectId);
    }

    @Test
    void changeProjectMemberRole_Success() {
        ChangeRoleResponse mockResponse = ChangeRoleResponse.newBuilder()
                .setChangedMemberId(memberId.toString())
                .setRole(ru.taska.api.project.v1.ProjectRole.VIEWER)
                .setProjectId(projectId.toString())
                .build();

        Mockito.when(projectMemberMapper.toProjectRole(ru.taska.api.project.v1.ProjectRole.VIEWER)).thenReturn(ProjectRole.VIEWER);
        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId)).thenReturn(Mono.just(1L));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberId, ru.taska.api.project.v1.ProjectRole.VIEWER, projectId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectMemberRepository).updateRole(memberId, ProjectRole.VIEWER, projectId);
    }

    @Test
    void changeProjectMemberRole_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberMapper.toProjectRole(ru.taska.api.project.v1.ProjectRole.VIEWER)).thenReturn(ProjectRole.VIEWER);
        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId)).thenReturn(Mono.just(0L));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberId, ru.taska.api.project.v1.ProjectRole.VIEWER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project member with id " + memberId + " was not found in project with id " + projectId, exception.getMessage());
                })
                .verify();

        Mockito.verify(projectMemberRepository).updateRole(memberId, ProjectRole.VIEWER, projectId);
    }
}