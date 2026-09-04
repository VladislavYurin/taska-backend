package ru.taska.service.worklog;

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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorklogService Unit Tests")
class WorklogServiceImplTest {

    @Mock
    private WorklogRepository worklogRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueProperties.AllowedRoles allowedRoles;

    @Mock
    private WorklogExecutor worklogExecutor;

    @InjectMocks
    private WorklogServiceImpl worklogService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKLOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    private Issue issue;
    private Worklog worklog;
    private CreateWorklogDto createWorklogDto;
    private UpdateWorklogDto updateWorklogDto;

    @BeforeEach
    void setUp() {
        issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .version(1)
                .timeSpentMinutes(0)
                .remainingEstimateMinutes(null)
                .build();

        worklog = Worklog.builder()
                .id(WORKLOG_ID)
                .issueId(ISSUE_ID)
                .projectId(PROJECT_ID)
                .authorUserId(MEMBER_ID)
                .spentMinutes(30)
                .workDate(LocalDate.now())
                .version(1)
                .build();
    }

    // ===== ADD WORKLOG =====

    @Test
    @DisplayName("addIssueWorklog: должен успешно добавить worklog при валидных данных")
    void shouldAddWorklogSuccessfully() {
        var dto = new CreateWorklogDto(30, LocalDate.now(), "comment");

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN,ProjectRole.MEMBER));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .thenReturn(Mono.just(worklog));

        Mono<Worklog> result = worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID,
                ISSUE_ID, MEMBER_ID, dto);

        StepVerifier.create(result)
                .expectNext(worklog)
                .verifyComplete();

        verify(issueRepository).findActiveById(ISSUE_ID);
        verify(projectRoleChecker).checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, MEMBER_ID,
                Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        verify(worklogExecutor).executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto);
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить spentMinutes <= 0")
    void shouldRejectNonPositiveSpentMinutesOnAdd() {
        var dto = new CreateWorklogDto(0, LocalDate.now(), "comment");
        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.empty());
        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("spentMinutes must be greater than 0");
                })
                .verify();

        verify(worklogExecutor, never()).executeAdd(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить workDate дальше maxFutureDays")
    void shouldRejectWorkDateTooFarInFutureOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now().plusDays(10), "comment");
        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("workDate is too far in the future");
                })
                .verify();

        verify(worklogExecutor, never()).executeAdd(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен выбросить NOT_FOUND если issue не найден")
    void shouldThrowNotFoundWhenIssueMissingOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now().plusDays(1), "comment");

        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        verify(worklogExecutor, never()).executeAdd(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен выбросить PERMISSION_DENIED если роль не разрешена")
    void shouldThrowPermissionDeniedWhenRoleNotAllowedOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now(), "comment");

        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addWorklogRoles()).thenReturn(Collections.emptySet());
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        verify(worklogExecutor, never()).executeAdd(any(), any(), any(), any(), any());
    }

    // ===== UPDATE WORKLOG =====

    @Test
    @DisplayName("updateIssueWorklog: автор должен успешно обновить свой worklog")
    void shouldAllowAuthorToUpdateOwnWorklog() {
        var dto = new UpdateWorklogDto(30, LocalDate.now(), "comment");

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.updateWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .thenReturn(Mono.just(worklog));

        Mono<Worklog> result = worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID,
                ISSUE_ID,WORKLOG_ID, MEMBER_ID, dto);

        StepVerifier.create(result)
                .expectNext(worklog)
                .verifyComplete();

        verify(allowedRoles).updateWorklogRoles();
        verify(allowedRoles, never()).manageWorklogRoles();
        verify(worklogExecutor).executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto);
    }

    @Test
    @DisplayName("updateIssueWorklog: не-автор MEMBER не должен обновить чужой worklog")
    void shouldRejectNonAuthorMemberUpdatingOthersWorklog() {
        var dto = new UpdateWorklogDto(45, null, null);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        ArgumentCaptor<Set<ProjectRole>> rolesCaptor = ArgumentCaptor.forClass(Set.class);
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), rolesCaptor.capture()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, OTHER_MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        Assertions.assertThat(rolesCaptor.getValue()).containsExactly(ProjectRole.ADMIN);
        verify(worklogExecutor, never()).executeUpdate(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateIssueWorklog: ADMIN должен обновить чужой worklog")
    void shouldAllowAdminToUpdateAnyWorklog() {
        var dto = new UpdateWorklogDto(45, null, null);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID, dto))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID, dto))
                .expectNext(worklog)
                .verifyComplete();

        verify(allowedRoles).manageWorklogRoles();
    }

    @Test
    @DisplayName("updateIssueWorklog: должен выбросить NOT_FOUND если worklog не принадлежит issue")
    void shouldThrowNotFoundWhenWorklogDoesNotBelongToIssue() {
        var dto = new UpdateWorklogDto(45, null, null);
        var foreignWorklog = worklog.toBuilder().issueId(UUID.randomUUID()).build();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(foreignWorklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.updateWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        // требуется из-за eager evaluation .then(checkProjectRole(...)) — подписки не будет,
        // но метод вызывается как аргумент до срабатывания validateIssueBelongsToWorklog
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue doesnt belongs to worklog");
                })
                .verify();

        verify(worklogExecutor, never()).executeUpdate(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateIssueWorklog: должен выбросить NOT_FOUND если worklog не найден")
    void shouldThrowNotFoundWhenWorklogMissingOnUpdate() {
        var dto = new UpdateWorklogDto(45, null, null);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("not found");
                })
                .verify();
    }

    @Test
    @DisplayName("updateIssueWorklog: должен отклонить spentMinutes <= 0 при наличии значения")
    void shouldRejectNonPositiveSpentMinutesOnUpdate() {
        var dto = new UpdateWorklogDto(0, null, null);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("spentMinutes must be greater than 0");
                })
                .verify();

        verify(worklogRepository, never()).findActiveById(any());
    }

    @Test
    @DisplayName("updateIssueWorklog: должен отклонить workDate дальше maxFutureDays при наличии значения")
    void shouldRejectWorkDateTooFarInFutureOnUpdate() {
        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.empty());
        var dto = new UpdateWorklogDto(null, LocalDate.now().plusDays(10), null);

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("workDate is too far in the future");
                })
                .verify();
    }

    // ===== DELETE WORKLOG =====

    @Test
    @DisplayName("deleteIssueWorklog: автор должен успешно удалить свой worklog")
    void shouldAllowAuthorToDeleteOwnWorklog() {
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.deleteWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .expectNext(worklog)
                .verifyComplete();

        verify(allowedRoles).deleteWorklogRoles();
        verify(allowedRoles, never()).manageWorklogRoles();
    }

    @Test
    @DisplayName("deleteIssueWorklog: не-автор MEMBER не должен удалить чужой worklog")
    void shouldRejectNonAuthorMemberDeletingOthersWorklog() {
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));
        Mockito.when(worklogExecutor.executeDelete(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, OTHER_MEMBER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();
    }


    @Test
    @DisplayName("deleteIssueWorklog: ADMIN должен удалить чужой worklog")
    void shouldAllowAdminToDeleteAnyWorklog() {
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID))
                .expectNext(worklog)
                .verifyComplete();
    }

    @Test
    @DisplayName("deleteIssueWorklog: должен выбросить NOT_FOUND если worklog не принадлежит issue")
    void shouldThrowNotFoundWhenWorklogDoesNotBelongToIssueOnDelete() {
        var foreignWorklog = worklog.toBuilder().issueId(UUID.randomUUID()).build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(foreignWorklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.deleteWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue doesnt belongs to worklog");
                })
                .verify();
    }



    @Test
    @DisplayName("deleteIssueWorklog: должен выбросить NOT_FOUND если worklog не найден")
    void shouldThrowNotFoundWhenWorklogMissingOnDelete() {
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();
    }

    // ===== LIST WORKLOG =====

    @Test
    @DisplayName("listIssueWorklog: VIEWER должен успешно получить список worklogs")
    void shouldAllowViewerToListWorklogs() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listWorklogRoles())
                .thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogRepository.findActiveByIssueId(ISSUE_ID))
                .thenReturn(Flux.just(worklog, worklog.toBuilder().id(UUID.randomUUID()).build()));

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID))
                .assertNext(list -> Assertions.assertThat(list).hasSize(2))
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssueWorklog: должен вернуть пустой список если worklogs нет")
    void shouldReturnEmptyListWhenNoWorklogs() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listWorklogRoles())
                .thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogRepository.findActiveByIssueId(ISSUE_ID)).thenReturn(Flux.empty());

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID))
                .assertNext(list -> Assertions.assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssueWorklog: должен выбросить NOT_FOUND если issue не найден")
    void shouldThrowNotFoundWhenIssueMissingOnList() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, PROJECT_ID, ISSUE_ID, MEMBER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        verify(worklogRepository, never()).findActiveByIssueId(any());
        verify(projectRoleChecker, never()).checkProjectRole(any(), any(), any(), any(), any());
    }
}