package ru.taska.service.patch;

import nullable.NullableField;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.PatchIssueResult;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class IssuePatchServiceImplTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IssueProperties issueProperties;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssuePatchExecutor executor;

    @InjectMocks
    private IssuePatchServiceImpl service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CURRENT_ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID NEW_ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final int VERSION = 3;
    private static final Instant UPDATED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final LocalDate START_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 10);

    private static final Set<ProjectRole> UPDATE_ROLES = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);
    private static final Set<ProjectRole> ASSIGN_ROLES = Set.of(ProjectRole.ADMIN);

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(issueProperties.allowedRoles().updateIssueRoles()).thenReturn(UPDATE_ROLES);
        Mockito.lenient().when(issueProperties.allowedRoles().assignIssueRoles()).thenReturn(ASSIGN_ROLES);
    }

    @Test
    @DisplayName("Должен выбросить NOT_FOUND и не проверять права, если задача не найдена")
    void patchIssue_shouldThrowNotFound_whenIssueNotFound() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(patchIssue(VERSION, emptyPatch()))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.NOT_FOUND))
                .verify();

        Mockito.verifyNoInteractions(projectRoleChecker, executor);
    }

    @Test
    @DisplayName("Должен выбросить PERMISSION_DENIED, если инициатор не прошёл проверку updateIssueRoles")
    void patchIssue_shouldThrowPermissionDenied_whenActorHasNoUpdateRole() {
        var expectedException = new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied");
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.error(expectedException));

        StepVerifier.create(patchIssue(VERSION, emptyPatch()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("При несовпадении версии должен вернуть исходную задачу с versionConflict=true без вызова executor")
    void patchIssue_shouldReturnVersionConflict_whenVersionMismatch() {
        var issue = baseIssue().build();
        var issueSnapshot = issue.toBuilder().build();
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());

        var patch = patch(Optional.of("new summary"), NullableField.absent(), NullableField.absent(), NullableField.absent());

        StepVerifier.create(patchIssue(VERSION - 1, patch))
                .assertNext(result -> {
                    Assertions.assertThat(result.versionConflict()).isTrue();
                    Assertions.assertThat(result.issue()).isSameAs(issue);
                    Assertions.assertThat(result.issue().getVersion()).isEqualTo(VERSION);
                    Assertions.assertThat(result.issue().getUpdatedAt()).isEqualTo(UPDATED_AT);
                })
                .verifyComplete();

        Assertions.assertThat(issue).isEqualTo(issueSnapshot);
        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("При несовпадении версии и start > due в патче должен вернуть конфликт версий, а не INVALID_ARGUMENT")
    void patchIssue_shouldReturnVersionConflict_whenVersionMismatchAndDatesInvalid() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());

        var patch = datesPatch(NullableField.of(DUE_DATE), NullableField.of(START_DATE));

        StepVerifier.create(patchIssue(VERSION - 1, patch))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isTrue())
                .verifyComplete();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("При несовпадении версии и смене исполнителя не должен проверять assignIssueRoles")
    void patchIssue_shouldNotCheckAssignRoles_whenVersionMismatch() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());

        StepVerifier.create(patchIssue(VERSION - 1, assigneePatch(NullableField.of(NEW_ASSIGNEE_ID))))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isTrue())
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("При несовпадении версии и отсутствии прав должен выбросить PERMISSION_DENIED, а не конфликт версий")
    void patchIssue_shouldThrowPermissionDenied_whenVersionMismatchAndNoRights() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES,
                Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(patchIssue(VERSION - 1, emptyPatch()))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.PERMISSION_DENIED))
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Патч без смены исполнителя должен передать исходные аргументы в executor и вернуть его результат")
    void patchIssue_shouldDelegateToExecutor_whenAssigneeNotChanged() {
        var expectedResult = new PatchIssueResult(baseIssue().version(VERSION + 1).build(), false);
        var patch = patch(Optional.of("new summary"), NullableField.absent(),
                NullableField.absent(), NullableField.absent());
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, patch))
                .expectNextMatches(result -> result == expectedResult)
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
        Mockito.verify(executor).executePatch(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(VERSION), Mockito.same(patch));
    }

    static Stream<Arguments> invalidDates() {
        return Stream.of(
                Arguments.argumentSet("start > due заданы в патче",
                        START_DATE, DUE_DATE,
                        NullableField.of(DUE_DATE.plusDays(1)), NullableField.of(DUE_DATE)),
                Arguments.argumentSet("в патче только startDate, и она позже текущей dueDate",
                        START_DATE, DUE_DATE,
                        NullableField.of(DUE_DATE.plusDays(1)), NullableField.absent()),
                Arguments.argumentSet("в патче только dueDate, и она раньше текущей startDate",
                        START_DATE, DUE_DATE,
                        NullableField.absent(), NullableField.of(START_DATE.minusDays(1)))
        );
    }

    @ParameterizedTest
    @MethodSource("invalidDates")
    @DisplayName("Должен выбросить INVALID_ARGUMENT, если после применения патча start > due")
    void patchIssue_shouldThrowInvalidArgument_whenStartAfterDue(
            LocalDate issueStart,
            LocalDate issueDue,
            NullableField<LocalDate> patchStart,
            NullableField<LocalDate> patchDue
    ) {
        var issue = baseIssue().startDate(issueStart).dueDate(issueDue).build();
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());

        StepVerifier.create(patchIssue(VERSION, datesPatch(patchStart, patchDue)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Не должен выбрасывать ошибку, если start == due")
    void patchIssue_shouldPass_whenStartEqualsDue() {
        var expectedResult = new PatchIssueResult(baseIssue().build(), false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, datesPatch(NullableField.of(DUE_DATE), NullableField.of(DUE_DATE))))
                .expectNext(expectedResult)
                .verifyComplete();
    }

    static Stream<Arguments> datesCheckSkipped() {
        return Stream.of(
                Arguments.argumentSet("патч очищает startDate, хотя в задаче start > due",
                        DUE_DATE, START_DATE,
                        NullableField.of(null), NullableField.absent()),
                Arguments.argumentSet("патч очищает dueDate, хотя в задаче start > due",
                        DUE_DATE, START_DATE,
                        NullableField.absent(), NullableField.of(null)),
                Arguments.argumentSet("в задаче нет dueDate, в патче она не передана",
                        START_DATE, null,
                        NullableField.of(DUE_DATE.plusDays(1)), NullableField.absent()),
                Arguments.argumentSet("в задаче нет startDate, в патче она не передана",
                        null, DUE_DATE,
                        NullableField.absent(), NullableField.of(START_DATE.minusDays(1)))
        );
    }

    @ParameterizedTest
    @MethodSource("datesCheckSkipped")
    @DisplayName("Должен пропустить проверку дат, если после применения патча одна из дат не задана")
    void patchIssue_shouldSkipDatesCheck_whenOneOfDatesIsNull(
            LocalDate issueStart,
            LocalDate issueDue,
            NullableField<LocalDate> patchStart,
            NullableField<LocalDate> patchDue
    ) {
        var issue = baseIssue().startDate(issueStart).dueDate(issueDue).build();
        var expectedResult = new PatchIssueResult(issue, false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, datesPatch(patchStart, patchDue)))
                .expectNext(expectedResult)
                .verifyComplete();
    }

    @Test
    @DisplayName("При некорректных датах и смене исполнителя должен выбросить INVALID_ARGUMENT без проверки assignIssueRoles")
    void patchIssue_shouldNotCheckAssignRoles_whenDatesInvalid() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());

        var patch = patch(Optional.empty(), NullableField.of(NEW_ASSIGNEE_ID),
                NullableField.of(DUE_DATE), NullableField.of(START_DATE));

        StepVerifier.create(patchIssue(VERSION, patch))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Не должен проверять assignIssueRoles, если передан текущий исполнитель задачи")
    void patchIssue_shouldNotCheckAssignRoles_whenAssigneeIsSame() {
        var expectedResult = new PatchIssueResult(baseIssue().build(), false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(CURRENT_ASSIGNEE_ID))))
                .expectNext(expectedResult)
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
    }

    @Test
    @DisplayName("При назначении другого пользователя должен проверить инициатора и нового исполнителя по assignIssueRoles")
    void patchIssue_shouldCheckActorAndAssignee_whenAssigningAnotherUser() {
        var expectedResult = new PatchIssueResult(baseIssue().build(), false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubRoleCheck(ACTOR_USER_ID, ASSIGN_ROLES, Mono.empty());
        stubRoleCheck(NEW_ASSIGNEE_ID, ASSIGN_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(NEW_ASSIGNEE_ID))))
                .expectNext(expectedResult)
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, ASSIGN_ROLES);
        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, NEW_ASSIGNEE_ID, ASSIGN_ROLES);
        Mockito.verify(executor).executePatch(
                Mockito.anyString(), Mockito.anyString(), Mockito.any(UUID.class),
                Mockito.any(UUID.class), Mockito.anyInt(), Mockito.any(IssuePatch.class));
    }

    @Test
    @DisplayName("При назначении самого себя должен проверить по assignIssueRoles только инициатора один раз")
    void patchIssue_shouldCheckOnlyActor_whenAssigningSelf() {
        var expectedResult = new PatchIssueResult(baseIssue().build(), false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubRoleCheck(ACTOR_USER_ID, ASSIGN_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(ACTOR_USER_ID))))
                .expectNext(expectedResult)
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verify(projectRoleChecker, Mockito.times(1))
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, ASSIGN_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
    }

    @Test
    @DisplayName("При снятии исполнителя должен проверить по assignIssueRoles только инициатора")
    void patchIssue_shouldCheckOnlyActor_whenUnassigning() {
        var expectedResult = new PatchIssueResult(baseIssue().assigneeId(null).build(), false);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubRoleCheck(ACTOR_USER_ID, ASSIGN_ROLES, Mono.empty());
        stubExecutor(Mono.just(expectedResult));

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(null))))
                .expectNext(expectedResult)
                .verifyComplete();

        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, UPDATE_ROLES);
        Mockito.verify(projectRoleChecker)
                .checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, ASSIGN_ROLES);
        Mockito.verifyNoMoreInteractions(projectRoleChecker);
    }

    @Test
    @DisplayName("Если инициатор не прошёл assignIssueRoles, должен выбросить PERMISSION_DENIED без проверки нового исполнителя")
    void patchIssue_shouldThrowPermissionDenied_whenActorHasNoAssignRole() {
        var expectedException = new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied");
        PublisherProbe<Void> assigneeCheck = PublisherProbe.empty();
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubRoleCheck(ACTOR_USER_ID, ASSIGN_ROLES, Mono.error(expectedException));
        stubRoleCheck(NEW_ASSIGNEE_ID, ASSIGN_ROLES, assigneeCheck.mono());

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(NEW_ASSIGNEE_ID))))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        assigneeCheck.assertWasNotSubscribed();
        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Если новый исполнитель не прошёл assignIssueRoles, должен выбросить PERMISSION_DENIED")
    void patchIssue_shouldThrowPermissionDenied_whenAssigneeHasNoAssignRole() {
        var expectedException = new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied");
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubRoleCheck(ACTOR_USER_ID, ASSIGN_ROLES, Mono.empty());
        stubRoleCheck(NEW_ASSIGNEE_ID, ASSIGN_ROLES, Mono.error(expectedException));

        StepVerifier.create(patchIssue(VERSION, assigneePatch(NullableField.of(NEW_ASSIGNEE_ID))))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("Должен пробросить ошибку executor как есть")
    void patchIssue_shouldPropagateExecutorError() {
        var expectedException = new DomainException(DomainStatus.NOT_FOUND, "Issue was not found");
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(baseIssue().build()));
        stubRoleCheck(ACTOR_USER_ID, UPDATE_ROLES, Mono.empty());
        stubExecutor(Mono.error(expectedException));

        StepVerifier.create(patchIssue(VERSION, emptyPatch()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();
    }

    @Test
    @DisplayName("Story points из патча приводятся к масштабу колонки story_points")
    void applyTo_shouldNormalizeStoryPointsScale() {
        var issue = baseIssue().storyPoints(new BigDecimal("3.00")).build();

        var patched = storyPointsPatch(NullableField.of(new BigDecimal("3.0"))).applyTo(issue);

        Assertions.assertThat(patched.getStoryPoints()).isEqualTo(new BigDecimal("3.00"));
    }

    @Test
    @DisplayName("Story points с большим числом знаков округляются так же, как это делает БД")
    void applyTo_shouldRoundStoryPointsHalfUp() {
        var patched = storyPointsPatch(NullableField.of(new BigDecimal("2.345"))).applyTo(baseIssue().build());

        Assertions.assertThat(patched.getStoryPoints()).isEqualTo(new BigDecimal("2.35"));
    }

    @Test
    @DisplayName("Очистка story points через патч оставляет null")
    void applyTo_shouldKeepNull_whenStoryPointsCleared() {
        var issue = baseIssue().storyPoints(new BigDecimal("3.00")).build();

        var patched = storyPointsPatch(NullableField.of(null)).applyTo(issue);

        Assertions.assertThat(patched.getStoryPoints()).isNull();
    }

    private Mono<PatchIssueResult> patchIssue(int ifMatchVersion, IssuePatch patch) {
        return service.patchIssue(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, ifMatchVersion, patch);
    }

    private void stubRoleCheck(UUID userId, Set<ProjectRole> roles, Mono<Void> result) {
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, userId, roles))
                .thenReturn(result);
    }

    private void stubExecutor(Mono<PatchIssueResult> result) {
        Mockito.when(executor.executePatch(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anyInt(),
                        Mockito.any(IssuePatch.class)
                ))
                .thenReturn(result);
    }

    private static void assertDomainStatus(Throwable error, DomainStatus status) {
        Assertions.assertThat(error).isInstanceOf(DomainException.class);
        Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(status);
    }

    private static Issue.IssueBuilder baseIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .summary("summary")
                .priority(IssuePriority.MEDIUM)
                .assigneeId(CURRENT_ASSIGNEE_ID)
                .startDate(START_DATE)
                .dueDate(DUE_DATE)
                .version(VERSION)
                .updatedAt(UPDATED_AT);
    }

    private static IssuePatch emptyPatch() {
        return patch(Optional.empty(), NullableField.absent(), NullableField.absent(), NullableField.absent());
    }

    private static IssuePatch assigneePatch(NullableField<UUID> assigneeId) {
        return patch(Optional.empty(), assigneeId, NullableField.absent(), NullableField.absent());
    }

    private static IssuePatch datesPatch(NullableField<LocalDate> startDate, NullableField<LocalDate> dueDate) {
        return patch(Optional.empty(), NullableField.absent(), startDate, dueDate);
    }

    private static IssuePatch storyPointsPatch(NullableField<BigDecimal> storyPoints) {
        return new IssuePatch(
                Optional.empty(),
                Optional.empty(),
                NullableField.absent(),
                NullableField.absent(),
                storyPoints,
                NullableField.absent(),
                NullableField.absent(),
                NullableField.absent(),
                NullableField.absent()
        );
    }

    private static IssuePatch patch(
            Optional<String> summary,
            NullableField<UUID> assigneeId,
            NullableField<LocalDate> startDate,
            NullableField<LocalDate> dueDate
    ) {
        return new IssuePatch(
                summary,
                Optional.empty(),
                NullableField.absent(),
                assigneeId,
                NullableField.absent(),
                startDate,
                dueDate,
                NullableField.absent(),
                NullableField.absent()
        );
    }
}
