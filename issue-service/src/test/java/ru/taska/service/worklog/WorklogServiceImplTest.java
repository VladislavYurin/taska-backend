package ru.taska.service.worklog;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.ProjectRole;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.Worklog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.transport.grpc.profile.GrpcAuthServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    @Mock
    private GrpcAuthServiceClient grpcAuthServiceClient;

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
        org.mockito.Mockito.lenient().when(projectRoleChecker.checkProjectWriteRole(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet())).thenReturn(Mono.empty());

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
        Mockito.when(allowedRoles.addWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER));
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .thenReturn(Mono.just(worklog));

        Mono<Worklog> result = worklogService.addIssueWorklog(REQUEST_ID, NODE_ID,
                ISSUE_ID, MEMBER_ID, dto);

        StepVerifier.create(result)
                .expectNext(worklog)
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectWriteRole(REQUEST_ID, NODE_ID, PROJECT_ID, MEMBER_ID,
                Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.verify(worklogExecutor).executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto);
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить spentMinutes <= 0")
    void shouldRejectNonPositiveSpentMinutesOnAdd() {
        var dto = new CreateWorklogDto(0, LocalDate.now(), "comment");
        Mockito.when(issueRepository.findActiveById(ArgumentMatchers.any())).thenReturn(Mono.empty());
        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("SpentMinutes must be greater than 0");
                })
                .verify();

        Mockito.verify(worklogExecutor, Mockito.never()).executeAdd(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить workDate дальше maxFutureDays")
    void shouldRejectWorkDateTooFarInFutureOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now().plusDays(10), "comment");
        Mockito.when(issueRepository.findActiveById(ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("WorkDate is too far in the future");
                })
                .verify();

        Mockito.verify(worklogExecutor, Mockito.never()).executeAdd(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен выбросить NOT_FOUND если issue не найден")
    void shouldThrowNotFoundWhenIssueMissingOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now().plusDays(1), "comment");

        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(worklogExecutor, Mockito.never()).executeAdd(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addIssueWorklog: должен выбросить PERMISSION_DENIED если роль не разрешена")
    void shouldThrowPermissionDeniedWhenRoleNotAllowedOnAdd() {
        var dto = new CreateWorklogDto(1, LocalDate.now(), "comment");

        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addWorklogRoles()).thenReturn(Collections.emptySet());
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));

        StepVerifier.create(worklogService.addIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        Mockito.verify(worklogExecutor, Mockito.never()).executeAdd(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .thenReturn(Mono.just(worklog));

        Mono<Worklog> result = worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID,
                ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto);

        StepVerifier.create(result)
                .expectNext(worklog)
                .verifyComplete();

        Mockito.verify(allowedRoles).updateWorklogRoles();
        Mockito.verify(allowedRoles, Mockito.never()).manageWorklogRoles();
        Mockito.verify(worklogExecutor).executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto);
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), rolesCaptor.capture()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, OTHER_MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        Assertions.assertThat(rolesCaptor.getValue()).containsExactly(ProjectRole.ADMIN);
        Mockito.verify(worklogExecutor, Mockito.never()).executeUpdate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateIssueWorklog: ADMIN должен обновить чужой worklog")
    void shouldAllowAdminToUpdateAnyWorklog() {
        var dto = new UpdateWorklogDto(45, null, null);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID, dto))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID, dto))
                .expectNext(worklog)
                .verifyComplete();

        Mockito.verify(allowedRoles).manageWorklogRoles();
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue doesnt belongs to worklog");
                })
                .verify();

        Mockito.verify(worklogExecutor, Mockito.never()).executeUpdate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateIssueWorklog: должен выбросить NOT_FOUND если worklog не найден")
    void shouldThrowNotFoundWhenWorklogMissingOnUpdate() {
        var dto = new UpdateWorklogDto(45, null, null);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
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

        Mockito.when(issueRepository.findActiveById(ArgumentMatchers.any())).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("SpentMinutes must be greater than 0");
                })
                .verify();

        Mockito.verify(worklogRepository, Mockito.never()).findActiveById(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateIssueWorklog: должен отклонить workDate дальше maxFutureDays при наличии значения")
    void shouldRejectWorkDateTooFarInFutureOnUpdate() {
        Mockito.when(issueProperties.maxFutureDays()).thenReturn(1);
        Mockito.when(issueRepository.findActiveById(ArgumentMatchers.any())).thenReturn(Mono.empty());
        var dto = new UpdateWorklogDto(null, LocalDate.now().plusDays(10), null);

        StepVerifier.create(worklogService.updateIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getMessage()).contains("WorkDate is too far in the future");
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
                .expectNext(worklog)
                .verifyComplete();

        Mockito.verify(allowedRoles).deleteWorklogRoles();
        Mockito.verify(allowedRoles, Mockito.never()).manageWorklogRoles();
    }

    @Test
    @DisplayName("deleteIssueWorklog: не-автор MEMBER не должен удалить чужой worklog")
    void shouldRejectNonAuthorMemberDeletingOthersWorklog() {
        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.manageWorklogRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));
        Mockito.when(worklogExecutor.executeDelete(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, OTHER_MEMBER_ID))
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID))
                .thenReturn(Mono.just(worklog));

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ADMIN_ID))
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
        Mockito.when(projectRoleChecker.checkProjectWriteRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogExecutor.executeDelete(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
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

        StepVerifier.create(worklogService.deleteIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, MEMBER_ID))
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
        Mockito.when(projectRoleChecker.checkProjectRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogRepository.findActivePageWithCount(ISSUE_ID, 20, 0L))
                .thenReturn(Mono.just(new ru.taska.domain.aggregate.WorklogRows(
                        java.util.List.of(worklog, worklog.toBuilder().id(UUID.randomUUID()).build()), 3L)));
        Mockito.when(grpcAuthServiceClient.getUserProfiles(Set.of(MEMBER_ID), REQUEST_ID, NODE_ID))
                .thenReturn(Mono.just(Map.of(MEMBER_ID, new UserSummary(MEMBER_ID, "Former member", null))));

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, 0, 20))
                .assertNext(list -> {
                    Assertions.assertThat(list.items()).hasSize(2);
                    Assertions.assertThat(list.totalCount()).isEqualTo(3);
                    Assertions.assertThat(list.hasMore()).isTrue();
                    Assertions.assertThat(list.authorProfiles().get(MEMBER_ID).displayName()).isEqualTo("Former member");
                })
                .verifyComplete();
        Mockito.verify(grpcAuthServiceClient).getUserProfiles(Set.of(MEMBER_ID), REQUEST_ID, NODE_ID);
    }

    @Test
    @DisplayName("listIssueWorklog: должен вернуть пустой список если worklogs нет")
    void shouldReturnEmptyListWhenNoWorklogs() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listWorklogRoles())
                .thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
        Mockito.when(worklogRepository.findActivePageWithCount(ISSUE_ID, 20, 0L))
                .thenReturn(Mono.just(new ru.taska.domain.aggregate.WorklogRows(java.util.List.of(), 0L)));

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, 0, 20))
                .assertNext(list -> {
                    Assertions.assertThat(list.items()).isEmpty();
                    Assertions.assertThat(list.totalCount()).isZero();
                    Assertions.assertThat(list.hasMore()).isFalse();
                })
                .verifyComplete();
        Mockito.verifyNoInteractions(grpcAuthServiceClient);
    }

    @Test
    @DisplayName("listIssueWorklog: должен выбросить NOT_FOUND если issue не найден")
    void shouldThrowNotFoundWhenIssueMissingOnList() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, 0, 20))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verifyNoInteractions(worklogRepository, grpcAuthServiceClient);
        Mockito.verify(projectRoleChecker, Mockito.never()).checkProjectRole(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void invalidPageShouldFailBeforeReadingSources(int page, int pageSize) {
        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, page, pageSize))
                .expectErrorSatisfies(error -> Assertions.assertThat(((DomainException) error).getStatus())
                        .isEqualTo(DomainStatus.INVALID_ARGUMENT))
                .verify();
        Mockito.verifyNoInteractions(issueRepository, worklogRepository, grpcAuthServiceClient, projectRoleChecker);
    }

    @Test
    void forbiddenReadShouldNotFetchWorklogsOrProfiles() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, OTHER_MEMBER_ID, Set.of(ProjectRole.MEMBER)))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Not a member")));
        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, OTHER_MEMBER_ID, 0, 20))
                .expectError(DomainException.class).verify();
        Mockito.verifyNoInteractions(worklogRepository, grpcAuthServiceClient);
    }

    @Test
    void profileFailureShouldKeepPageAndAuthorIds() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listWorklogRoles()).thenReturn(Set.of(ProjectRole.MEMBER));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, MEMBER_ID, Set.of(ProjectRole.MEMBER)))
                .thenReturn(Mono.empty());
        Mockito.when(worklogRepository.findActivePageWithCount(ISSUE_ID, 1, 2L))
                .thenReturn(Mono.just(new ru.taska.domain.aggregate.WorklogRows(java.util.List.of(worklog), 3L)));
        Mockito.when(grpcAuthServiceClient.getUserProfiles(Set.of(MEMBER_ID), REQUEST_ID, NODE_ID))
                .thenReturn(Mono.error(new IllegalStateException("Auth unavailable")));
        StepVerifier.create(worklogService.listIssueWorklog(REQUEST_ID, NODE_ID, ISSUE_ID, MEMBER_ID, 2, 1))
                .assertNext(page -> {
                    Assertions.assertThat(page.items()).containsExactly(worklog);
                    Assertions.assertThat(page.authorProfiles()).isEmpty();
                    Assertions.assertThat(page.hasMore()).isFalse();
                }).verifyComplete();
    }

}
