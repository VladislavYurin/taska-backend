package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.impl.IssueServiceImpl;
import ru.taska.transport.grpc.ProjectRoleChecker;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class DeleteIssueTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueHistoryRepository issueHistoryRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private IssueMapper issueMapper;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IssueProperties issueProperties;

    @InjectMocks
    private IssueServiceImpl issueService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UUID issueId;
    private UUID actorUserId;
    private String requestId;
    private String nodeId;
    private Issue mockIssue;
    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    public void setUp() {
        issueId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        requestId = "test-request-id";
        nodeId = "test-node-id";

        mockIssue = new Issue();
        mockIssue.setId(issueId);
        mockIssue.setDeletedAt(Instant.now());

        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );

        Mockito.when(issueProperties.allowedRoles().deleteIssueRoles()).thenReturn(allowedRoles);
        Mockito.when(projectRoleChecker.checkProjectRole(
                        requestId, nodeId, PROJECT_ID, actorUserId, allowedRoles)
                )
                .thenReturn(Mono.empty());
    }

    @DisplayName("Успешное мягкое удаление задачи")
    @Test
    public void testDeleteIssue_Success() {
        IssueHistory mockHistory = new IssueHistory();
        OutboxEvent mockOutbox = new OutboxEvent();

        Mockito.when(issueRepository.softDeleteAndReturn(issueId))
                .thenReturn(Mono.just(mockIssue));

        Mockito.when(issueMapper.buildIssueHistory(mockIssue, IssueEventType.DELETED, actorUserId))
                .thenReturn(mockHistory);

        Mockito.when(issueMapper.buildOutboxEvent(mockIssue, AggregateType.ISSUE.getValue(), EventType.ISSUE_DELETED, requestId))
                .thenReturn(mockOutbox);

        Mockito.when(issueHistoryRepository.save(mockHistory))
                .thenReturn(Mono.just(mockHistory));

        Mockito.when(outboxEventRepository.save(mockOutbox))
                .thenReturn(Mono.just(mockOutbox));

        Mono<Issue> resultMono = issueService.deleteIssue(requestId, nodeId, PROJECT_ID, issueId, actorUserId);

        StepVerifier.create(resultMono)
                .assertNext(deletedIssue -> {
                    Assertions.assertEquals(issueId, deletedIssue.getId());
                    Assertions.assertNotNull(deletedIssue.getDeletedAt());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueProperties.allowedRoles()).deleteIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                requestId, nodeId, PROJECT_ID, actorUserId, allowedRoles
        );
        Mockito.verify(issueRepository).softDeleteAndReturn(issueId);
        Mockito.verify(issueHistoryRepository).save(mockHistory);
        Mockito.verify(outboxEventRepository).save(mockOutbox);
        Mockito.verify(issueMapper).buildIssueHistory(mockIssue, IssueEventType.DELETED, actorUserId);
        Mockito.verify(issueMapper).buildOutboxEvent(mockIssue, AggregateType.ISSUE.getValue(), EventType.ISSUE_DELETED, requestId);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper);
    }

    @DisplayName("Выкидывание ошибки при отсутствии задачи в БД или если отмечена удаленной.")
    @Test
    public void testDeleteIssue_NotFound_ThrowsDomainException() {
        Mockito.when(issueRepository.softDeleteAndReturn(issueId))
                .thenReturn(Mono.empty());

        Mono<Issue> resultMono = issueService.deleteIssue(requestId, nodeId, PROJECT_ID, issueId, actorUserId);

        StepVerifier.create(resultMono)
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueProperties.allowedRoles()).deleteIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                requestId, nodeId, PROJECT_ID, actorUserId, allowedRoles
        );
        Mockito.verify(issueRepository).softDeleteAndReturn(issueId);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper);
    }
}