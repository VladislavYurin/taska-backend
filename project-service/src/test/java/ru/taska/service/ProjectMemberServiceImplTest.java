package ru.taska.service;

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
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectMembershipInfoDto;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.impl.ProjectMemberServiceImpl;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceImplTest {

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private ProjectMemberServiceImpl projectMemberService;

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
        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(false));
        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(mockMember));
        Mockito.when(outboxEventService.saveMemberAdded(ArgumentMatchers.any(), ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, memberId, initiatorId, ProjectRole.MEMBER, projectId))
                .expectNextMatches(pm -> pm.getUserId().equals(memberId) && pm.getProjectId().equals(projectId) && pm.getRole().equals(ProjectRole.MEMBER))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).existsByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(projectMemberRepository).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(outboxEventService).saveMemberAdded(ArgumentMatchers.any(), ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    void addProjectMember_ThrowsAlreadyExistsException_WhenMemberExists() {
        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(true));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, memberId, initiatorId, ProjectRole.MEMBER, projectId))
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
        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(1L));
        Mockito.when(outboxEventService.saveMemberRemoved(ArgumentMatchers.any(), eq(memberId), eq(projectId))).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberId, projectId))
                .expectNextMatches(pm -> pm.getUserId().equals(memberId) && pm.getProjectId().equals(projectId))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).deleteByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(outboxEventService).saveMemberRemoved(ArgumentMatchers.any(), eq(memberId), eq(projectId));
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
        Mockito.verify(outboxEventService, Mockito.never()).saveMemberRemoved(eq(requestId), Mockito.any(), Mockito.any());
    }

    @Test
    void changeProjectMemberRole_Success() {
        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId)).thenReturn(Mono.just(1L));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberId, ProjectRole.VIEWER, projectId))
                .expectNextMatches(pm -> pm.getUserId().equals(memberId) && pm.getProjectId().equals(projectId) && pm.getRole().equals(ProjectRole.VIEWER))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).updateRole(memberId, ProjectRole.VIEWER, projectId);
    }

    @Test
    void changeProjectMemberRole_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId)).thenReturn(Mono.just(0L));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberId, ProjectRole.VIEWER, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project member with id " + memberId + " was not found in project with id " + projectId, exception.getMessage());
                })
                .verify();

        Mockito.verify(projectMemberRepository).updateRole(memberId, ProjectRole.VIEWER, projectId);
    }

    @Test
    void checkProjectMemberRole_ProjectExists_AndUserIsMember() {
        Project mockProject = Project.builder().id(projectId).build();
        ProjectMembershipInfoDto mockDto = new ProjectMembershipInfoDto(ProjectRole.MEMBER, true, true);

        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(mockMember));

        StepVerifier.create(projectMemberService.checkProjectMemberRole(requestId, nodeId, projectId, memberId))
                .expectNext(mockDto)
                .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(memberId, projectId);
    }

    @Test
    void checkProjectMemberRole_ProjectExists_ButUserIsNotMember() {
        Project mockProject = Project.builder().id(projectId).build();
        ProjectMembershipInfoDto mockDto = new ProjectMembershipInfoDto(ProjectRole.UNSPECIFIED, false, true);

        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.empty());

        StepVerifier.create(projectMemberService.checkProjectMemberRole(requestId, nodeId, projectId, memberId))
                .expectNext(mockDto)
                .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(memberId, projectId);
    }

    @Test
    void checkProjectMemberRole_ProjectDoesNotExist() {
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.empty());
        ProjectMembershipInfoDto mockDto = new ProjectMembershipInfoDto(ProjectRole.UNSPECIFIED, false, false);

        StepVerifier.create(projectMemberService.checkProjectMemberRole(requestId, nodeId, projectId, memberId))
                .expectNext(mockDto)
                .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository, Mockito.never()).findByUserIdAndProjectId(Mockito.any(), Mockito.any());
    }
}