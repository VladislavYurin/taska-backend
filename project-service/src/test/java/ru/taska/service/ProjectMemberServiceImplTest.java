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
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.impl.ProjectMemberServiceImpl;

import java.util.UUID;

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
                .addedBy(initiatorId)
                .projectId(projectId)
                .role(ProjectRole.MEMBER)
                .build();
    }

    @Test
    void addProjectMember_Success() {
        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(false));
        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(mockMember));
        Mockito.when(outboxEventService.saveMemberAdded(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.addProjectMember(requestId, nodeId, memberId, initiatorId, ProjectRole.MEMBER, projectId))
                .expectNextMatches(actualMember ->
                        actualMember.getUserId().equals(memberId) &&
                                actualMember.getProjectId().equals(projectId) &&
                                actualMember.getRole() == ProjectRole.MEMBER &&
                                actualMember.getAddedAt() != null &&
                                actualMember.getAddedBy().equals(initiatorId))
                .verifyComplete();

        Mockito.verify(projectMemberRepository).existsByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(projectMemberRepository).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(outboxEventService).saveMemberAdded(ArgumentMatchers.any(ProjectMember.class));
    }

    @Test
    void addProjectMember_ThrowsAlreadyExistsException_WhenMemberExists() {
        Mockito.when(projectMemberRepository.existsByUserIdAndProjectId(memberId, projectId))
                .thenReturn(Mono.just(true));

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
        ProjectMember expectedDeletedMember = ProjectMember.builder()
                .userId(memberId)
                .projectId(projectId)
                .build();

        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId))
                .thenReturn(Mono.just(1L));
        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId)).thenReturn(Mono.just(1L));
        Mockito.when(outboxEventService.saveMemberRemoved(Mockito.any(), Mockito.any())).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectMemberService.rmProjectMember(requestId, nodeId, memberId, projectId))
                .expectNext(expectedDeletedMember)
                .verifyComplete();

        Mockito.verify(projectMemberRepository).deleteByUserIdAndProjectId(memberId, projectId);
        Mockito.verify(outboxEventService).saveMemberRemoved(Mockito.any(), Mockito.any());
    }

    @Test
    void rmProjectMember_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberRepository.deleteByUserIdAndProjectId(memberId, projectId))
                .thenReturn(Mono.just(0L));

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
        ProjectMember expectedChangedMember = ProjectMember.builder()
                .userId(memberId)
                .role(ProjectRole.VIEWER)
                .projectId(projectId)
                .build();

        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(projectMemberService.changeProjectMemberRole(requestId, nodeId, memberId, ProjectRole.VIEWER, projectId))
                .expectNext(expectedChangedMember)
                .verifyComplete();

        Mockito.verify(projectMemberRepository).updateRole(memberId, ProjectRole.VIEWER, projectId);
    }

    @Test
    void changeProjectMemberRole_ThrowsNotFoundException_WhenMemberDoesNotExist() {
        Mockito.when(projectMemberRepository.updateRole(memberId, ProjectRole.VIEWER, projectId))
                .thenReturn(Mono.just(0L));

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
}