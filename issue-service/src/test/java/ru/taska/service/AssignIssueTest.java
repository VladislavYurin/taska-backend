package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.UUID;

public class AssignIssueTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );

        Mockito.lenient().when(issueProperties.allowedRoles().assignIssueRoles()).thenReturn(allowedRoles);

        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.eq(PROJECT_ID),
                Mockito.any(),
                Mockito.eq(allowedRoles)
        )).thenReturn(Mono.empty());

        Mockito.lenient().when(issueAutoWatchService.watchAssigneeOnAssign(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(Issue.class), Mockito.any(UUID.class)))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Должен успешно назначить исполнителя и сохранить историю и outbox")
    void assignIssueSuccess() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);

        Issue updatedIssue = new Issue();
        updatedIssue.setId(ISSUE_ID);
        updatedIssue.setAssigneeId(ASSIGNEE_ID);
        updatedIssue.setProjectId(PROJECT_ID);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(updatedIssue));

        Mockito.when(payloadSerializer.createIssueAssignedPayload(Mockito.any(), Mockito.eq(ASSIGNEE_ID)))
                .thenReturn(payload);
        Mockito.when(issueHistoryService.saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(UUID.class),
                        Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.ASSIGNED), Mockito.eq(payload)))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(AggregateType.class),
                        Mockito.any(UUID.class), Mockito.eq(EventType.ISSUE_ASSIGNED), Mockito.eq(payload)))
                .thenReturn(Mono.empty());

        existingIssue.setAssigneeId(null);

        StepVerifier.create(issueService.assignIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ASSIGNEE_ID, ACTOR_USER_ID)
                )
                .expectNextMatches(result -> {
                    Assertions.assertThat(result.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
                    return true;
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).assignIssueRoles();
        Mockito.verify(projectRoleChecker, Mockito.times(2)).checkProjectRole(
                Mockito.anyString(), Mockito.anyString(), Mockito.eq(PROJECT_ID),
                Mockito.any(), Mockito.eq(allowedRoles)
        );
        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(issueRepository).save(Mockito.any(Issue.class));

        Mockito.verify(payloadSerializer).createIssueAssignedPayload(Mockito.any(), Mockito.eq(ASSIGNEE_ID));
        Mockito.verify(issueHistoryService).saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(UUID.class),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.ASSIGNED), Mockito.eq(payload));
        Mockito.verify(outboxEventService).saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(AggregateType.class),
                Mockito.any(UUID.class), Mockito.eq(EventType.ISSUE_ASSIGNED), Mockito.eq(payload));
        Mockito.verify(issueAutoWatchService).watchAssigneeOnAssign(
                REQUEST_ID, NODE_ID, updatedIssue, ACTOR_USER_ID);

        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryService, outboxEventService, payloadSerializer);
    }

    @Test
    @DisplayName("Не должен выполнять запись в БД и сохранять историю, если исполнитель не изменился")
    void assignIssue_AlreadyAssignedToSameUser_ShouldDoNothing() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setAssigneeId(ASSIGNEE_ID);
        existingIssue.setProjectId(PROJECT_ID);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));

        StepVerifier.create(issueService.assignIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID,
                        ASSIGNEE_ID, ACTOR_USER_ID)
                )
                .expectNext(existingIssue)
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).assignIssueRoles();
        Mockito.verify(projectRoleChecker, Mockito.times(2)).checkProjectRole(
                Mockito.anyString(), Mockito.anyString(), Mockito.eq(PROJECT_ID),
                Mockito.any(), Mockito.eq(allowedRoles)
        );
        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);

        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(payloadSerializer, issueHistoryService, outboxEventService, issueAutoWatchService);
    }

    @Test
    @DisplayName("Должен выбросить исключение NOT_FOUND, если задача для назначения не найдена")
    void assignIssue_IssueDeleted_ShouldNotAssignAndThrowException() {
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.assignIssue(
                        REQUEST_ID, NODE_ID,
                        ISSUE_ID, ASSIGNEE_ID, ACTOR_USER_ID)
                )
                .expectErrorMatches(throwable -> throwable instanceof DomainException &&
                        ((DomainException) throwable).getStatus() == DomainStatus.NOT_FOUND &&
                        throwable.getMessage().contains("not found"))
                .verify();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(projectRoleChecker, payloadSerializer, issueHistoryService, outboxEventService);
    }
}

