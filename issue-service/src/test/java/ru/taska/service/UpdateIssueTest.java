package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.ProjectRole;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
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
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class UpdateIssueTest {

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

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueProperties.AllowedRoles allowedRolesConfig;

    @InjectMocks
    private IssueServiceImpl issueService;

    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "node-1";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUpProps() {
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRolesConfig);
        Mockito.when(allowedRolesConfig.updateIssueRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
    }

    @DisplayName("Успешное обновление полей задачи")
    @Test
    void updateIssue_Success() {
        ReflectionTestUtils.setField(issueService, "objectMapper", new ObjectMapper());

        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setSummary("Old Summary");
        existingIssue.setDescription("Old Description");
        existingIssue.setPriority(IssuePriority.LOW);
        existingIssue.setVersion(1);

        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        IssueHistory history = new IssueHistory();
        OutboxEvent event = new OutboxEvent();

        Mockito.when(projectRoleChecker.checkProjectRole(
                Mockito.eq(REQUEST_ID),
                Mockito.eq(NODE_ID),
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID),
                Mockito.anySet()
        )).thenReturn(Mono.empty());

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(issueMapper.buildIssueHistory(existingIssue, IssueEventType.UPDATED, ACTOR_USER_ID)).thenReturn(history);
        Mockito.when(issueMapper.buildOutboxEvent(existingIssue, AggregateType.ISSUE.getValue(), EventType.ISSUE_UPDATED, REQUEST_ID)).thenReturn(event);

        Mockito.when(issueRepository.save(existingIssue)).thenReturn(Mono.just(existingIssue));
        Mockito.when(issueHistoryRepository.save(history)).thenReturn(Mono.just(history));
        Mockito.when(outboxEventRepository.save(event)).thenReturn(Mono.just(event));

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority))
                .assertNext(result -> {
                    Assertions.assertThat(result.getSummary()).isEqualTo(newSummary);
                    Assertions.assertThat(result.getDescription()).isEqualTo(newDescription);
                    Assertions.assertThat(result.getPriority()).isEqualTo(newPriority);
                    Assertions.assertThat(result.getVersion()).isEqualTo(2);
                })
                .verifyComplete();

        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.anySet());
        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueRepository).save(existingIssue);

        ArgumentCaptor<IssueHistory> historyCaptor = ArgumentCaptor.forClass(IssueHistory.class);
        Mockito.verify(issueHistoryRepository).save(historyCaptor.capture());
        Assertions.assertThat(historyCaptor.getValue()).isSameAs(history);
        Assertions.assertThat(historyCaptor.getValue().getPayload()).isNotNull();

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository).save(eventCaptor.capture());
        Assertions.assertThat(eventCaptor.getValue()).isSameAs(event);

        Mockito.verify(issueMapper).buildIssueHistory(existingIssue, IssueEventType.UPDATED, ACTOR_USER_ID);
        Mockito.verify(issueMapper).buildOutboxEvent(existingIssue, AggregateType.ISSUE.getValue(), EventType.ISSUE_UPDATED, REQUEST_ID);

        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper, projectRoleChecker);
    }

    @DisplayName("Возврат задачи без изменений, если переданные поля совпадают с текущими")
    @Test
    void updateIssue_NoChanges() {
        String sameSummary = "Same Summary";
        String sameDescription = "Same Description";
        IssuePriority samePriority = IssuePriority.MEDIUM;

        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setSummary(sameSummary);
        existingIssue.setDescription(sameDescription);
        existingIssue.setPriority(samePriority);
        existingIssue.setVersion(1);

        Mockito.when(projectRoleChecker.checkProjectRole(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.anySet()
        )).thenReturn(Mono.empty());

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(existingIssue));

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, ACTOR_USER_ID,
                        sameSummary, sameDescription, samePriority))
                .expectNext(existingIssue)
                .verifyComplete();

        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.anySet());
        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper, projectRoleChecker);
    }

    @DisplayName("Выбрасывание ошибки, если задачи нет или она отмечена удаленной")
    @Test
    void updateIssue_NotFound() {
        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        Mockito.when(projectRoleChecker.checkProjectRole(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.anySet()
        )).thenReturn(Mono.empty());

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(exception.getMessage()).contains("was not found");
                })
                .verify();

        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.anySet());
        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper, projectRoleChecker);
    }
}