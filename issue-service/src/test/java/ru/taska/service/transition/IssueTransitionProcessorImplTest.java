package ru.taska.service.transition;

import org.assertj.core.api.Assertions;
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
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.workflow.IssueTransitionValidator;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueTransitionProcessorImplTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IssueProperties issueProperties;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssueTransitionValidator validator;

    @Mock
    private IssueTransitionExecutor executor;

    @InjectMocks
    private IssueTransitionProcessorImpl processor;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TRANSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String PAYLOAD = "some-kind-of-payload";
    private static final String SOURCE_STATUS_KEY = "TODO";
    private static final String TARGET_STATUS_KEY = "IN_PROGRESS";

    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );
    }

    @Test
    @DisplayName("Должен передать выполнение в executor в случае успешной валидации")
    void transitionIssue_shouldCallExecutor_whenValidationSuccess() {
        var issue = Issue.builder()
                .projectId(PROJECT_ID)
                .build();

        var expectedResponse = Mockito.mock(IssueWithHistory.class);

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueProperties.allowedRoles().issueTransitionRoles())
                .thenReturn(allowedRoles);

        Mockito.when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()
                ))
                .thenReturn(Mono.empty());

        Mockito.when(validator.validateTransition(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(Issue.class),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anyString()

                ))
                .thenReturn(Mono.just(TARGET_STATUS_KEY));

        Mockito.when(executor.executeTransition(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(Mono.just(expectedResponse));

        StepVerifier.create(processor.transitionIssue(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TRANSITION_ID,
                        ACTOR_USER_ID,
                        PAYLOAD
                ))
                .expectNext(expectedResponse)
                .verifyComplete();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(projectRoleChecker, Mockito.times(1))
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles);

        Mockito.verify(validator, Mockito.times(1))
                .validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD);

        Mockito.verify(executor, Mockito.times(1))
                .executeTransition(REQUEST_ID, NODE_ID, ISSUE_ID, TARGET_STATUS_KEY, TRANSITION_ID, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом NOT_FOUND, если задача не найдена")
    void transitionIssue_shouldThrowException_whenIssueNotFound() {
        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(processor.transitionIssue(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TRANSITION_ID,
                        ACTOR_USER_ID,
                        PAYLOAD
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(Mockito.any(UUID.class));

        Mockito.verifyNoMoreInteractions(projectRoleChecker, validator, executor);
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом PERMISSION_DENIED, если пользователь не прошел проверку роли")
    void transitionIssue_shouldThrowException_whenProjectRoleCheckerFailed() {
        var issue = Issue.builder()
                .projectId(PROJECT_ID)
                .build();

        var expectedException = new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied");

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueProperties.allowedRoles().issueTransitionRoles())
                .thenReturn(allowedRoles);

        Mockito.when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()
                ))
                .thenReturn(Mono.error(expectedException));

        StepVerifier.create(processor.transitionIssue(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TRANSITION_ID,
                        ACTOR_USER_ID,
                        PAYLOAD
                ))
                .expectErrorSatisfies(error ->
                        Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(projectRoleChecker, Mockito.times(1))
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles);

        Mockito.verifyNoMoreInteractions(validator, executor, issueRepository);
    }

    @Test
    @DisplayName("Retry должен сработать только для executor при попытке конкурентной смене статуса")
    void transitionIssue_shouldRetryOnlyExecutor_whenOptimisticLockConflict() {
        var issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .statusKey(SOURCE_STATUS_KEY)
                .build();

        var expectedResponse = Mockito.mock(IssueWithHistory.class);

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueProperties.allowedRoles().issueTransitionRoles())
                .thenReturn(allowedRoles);

        Mockito.when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()
                ))
                .thenReturn(Mono.empty());

        Mockito.when(validator.validateTransition(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(Issue.class),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anyString()
                ))
                .thenReturn(Mono.just(TARGET_STATUS_KEY));

        Mockito.when(executor.executeTransition(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(Mono.error(new DomainException(DomainStatus.ABORTED, "Issue status was modified concurrently")),
                        Mono.just(expectedResponse));

        Mockito.when(issueProperties.retry().maxAttempts())
                .thenReturn(2);

        Mockito.when(issueProperties.retry().minBackoff())
                .thenReturn(Duration.ofMillis(1));

        StepVerifier.create(processor.transitionIssue(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TRANSITION_ID,
                        ACTOR_USER_ID,
                        PAYLOAD
                ))
                .expectNext(expectedResponse)
                .verifyComplete();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(projectRoleChecker, Mockito.times(1))
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles);

        Mockito.verify(validator, Mockito.times(1))
                .validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD);

        Mockito.verify(executor, Mockito.times(2))
                .executeTransition(REQUEST_ID, NODE_ID, ISSUE_ID, TARGET_STATUS_KEY, TRANSITION_ID, ACTOR_USER_ID);
    }

}
