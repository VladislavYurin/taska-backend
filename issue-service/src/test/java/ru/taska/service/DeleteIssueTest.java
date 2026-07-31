package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class DeleteIssueTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;
    private UUID localIssueId;
    private UUID localActorUserId;
    private String localRequestId;
    private String localNodeId;
    private Issue mockIssue;

    @BeforeEach
    public void setUp() {
        localIssueId = UUID.randomUUID();
        localActorUserId = UUID.randomUUID();
        localRequestId = "test-request-id";
        localNodeId = "test-node-id";

        mockIssue = new Issue();
        mockIssue.setId(localIssueId);
        mockIssue.setProjectId(PROJECT_ID);
        mockIssue.setDeletedAt(Instant.now());
        mockIssue.setAssigneeId(ASSIGNEE_ID);

        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );

        Mockito.lenient()
                .when(issueProperties.allowedRoles().deleteIssueRoles())
                .thenReturn(allowedRoles);

        Mockito.lenient()
                .when(projectRoleChecker.checkProjectRole(
                        localRequestId, localNodeId, PROJECT_ID, localActorUserId, allowedRoles)
                )
                .thenReturn(Mono.empty());
    }

    @DisplayName("Успешное мягкое удаление задачи")
    @Test
    public void testDeleteIssue_Success() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        Mockito.when(issueRepository.softDeleteAndReturn(localIssueId))
                .thenReturn(Mono.just(mockIssue));

        Mockito.when(payloadSerializer.createIssueDeletedPayload(Mockito.eq(IssueEventType.DELETED), Mockito.any(Instant.class), Mockito.eq(localActorUserId), Mockito.eq(ASSIGNEE_ID)))
                .thenReturn(payload);

        Mockito.when(issueHistoryService.saveIssueHistory(localRequestId, localNodeId, mockIssue, localActorUserId, IssueEventType.DELETED, payload))
                .thenReturn(Mono.empty());

        Mockito.when(outboxEventService.saveOutboxEvent(localRequestId, localNodeId, mockIssue.getId(), EventType.ISSUE_DELETED, payload))
                .thenReturn(Mono.empty());

        Mono<Issue> resultMono = issueService.deleteIssue(localRequestId, localNodeId, localIssueId, localActorUserId);

        StepVerifier.create(resultMono)
                .assertNext(deletedIssue -> {
                    Assertions.assertEquals(localIssueId, deletedIssue.getId());
                    Assertions.assertNotNull(deletedIssue.getDeletedAt());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueProperties.allowedRoles()).deleteIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                localRequestId, localNodeId, PROJECT_ID, localActorUserId, allowedRoles
        );
        Mockito.verify(issueRepository).softDeleteAndReturn(localIssueId);
        Mockito.verify(payloadSerializer).createIssueDeletedPayload(Mockito.eq(IssueEventType.DELETED), Mockito.any(Instant.class), Mockito.eq(localActorUserId), Mockito.eq(ASSIGNEE_ID));
        Mockito.verify(issueHistoryService).saveIssueHistory(localRequestId, localNodeId, mockIssue, localActorUserId, IssueEventType.DELETED, payload);
        Mockito.verify(outboxEventService).saveOutboxEvent(localRequestId, localNodeId, mockIssue.getId(), EventType.ISSUE_DELETED, payload);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryService, outboxEventService, projectRoleChecker, payloadSerializer);
    }

    @DisplayName("Выкидывание ошибки при отсутствии задачи в БД или если отмечена удаленной.")
    @Test
    public void testDeleteIssue_NotFound_ThrowsDomainException() {
        Mockito.when(issueRepository.softDeleteAndReturn(localIssueId))
                .thenReturn(Mono.empty());

        Mono<Issue> resultMono = issueService.deleteIssue(localRequestId, localNodeId, localIssueId, localActorUserId);

        StepVerifier.create(resultMono)
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueRepository).softDeleteAndReturn(localIssueId);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(payloadSerializer, issueHistoryService, outboxEventService, projectRoleChecker);
    }
}
