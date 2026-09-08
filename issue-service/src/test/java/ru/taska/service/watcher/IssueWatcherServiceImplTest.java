package ru.taska.service.watcher;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Тесты соответствуют актуальному API:
 * {@code listIssueWatchers(..., page, pageSize) -> Mono<PageResult>},
 * {@code getWatchState(issueId, actorUserId)} без requestId/nodeId.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueWatcherServiceImplTest {

    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TARGET_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID WATCHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueProperties.AllowedRoles allowedRoles;

    @Mock
    private IssueWatcherRepository issueWatcherRepository;

    @Mock
    private IssueWatcherExecutor executor;

    @InjectMocks
    private IssueWatcherServiceImpl service;

    private final IssueProperties.Pagination paginationConfig = new IssueProperties.Pagination(20, 100);

    private Issue issue;
    private IssueWatcher watcher;
    private Set<ProjectRole> watchRoles;
    private Set<ProjectRole> manageRoles;
    private Set<ProjectRole> listRoles;

    @BeforeEach
    void setUp() {
        issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .build();

        watcher = IssueWatcher.builder()
                .id(WATCHER_ID)
                .issueId(ISSUE_ID)
                .projectId(PROJECT_ID)
                .userId(ACTOR_USER_ID)
                .createdBy(ACTOR_USER_ID)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        watchRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);
        manageRoles = Set.of(ProjectRole.ADMIN);
        listRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER);

        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.watchIssueRoles()).thenReturn(watchRoles);
        Mockito.when(allowedRoles.manageWatchersRoles()).thenReturn(manageRoles);
        Mockito.when(allowedRoles.listWatchersRoles()).thenReturn(listRoles);
        Mockito.when(issueProperties.pagination()).thenReturn(paginationConfig);
    }

    @Test
    @DisplayName("watchIssue: должен подписать себя и вернуть count")
    void watchIssue_self_shouldSucceed() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, watchRoles))
                .thenReturn(Mono.empty());
        Mockito.when(executor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, ACTOR_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(watcher));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(3L));

        StepVerifier.create(service.watchIssue(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, null))
                .assertNext(result -> {
                    Assertions.assertThat(result.watcher()).isEqualTo(watcher);
                    Assertions.assertThat(result.watchersCount()).isEqualTo(3L);
                })
                .verifyComplete();

        Mockito.verify(allowedRoles).watchIssueRoles();
        Mockito.verify(executor).executeWatch(
                REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, ACTOR_USER_ID, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("watchIssue: подписка другого пользователя требует manageWatchersRoles")
    void watchIssue_forTarget_shouldUseManageRoles() {
        IssueWatcher targetWatcher = watcher.toBuilder().userId(TARGET_USER_ID).build();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, manageRoles))
                .thenReturn(Mono.empty());
        Mockito.when(executor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, TARGET_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(targetWatcher));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.watchIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, TARGET_USER_ID))
                .assertNext(result -> Assertions.assertThat(result.watcher().getUserId())
                        .isEqualTo(TARGET_USER_ID))
                .verifyComplete();

        Mockito.verify(allowedRoles).manageWatchersRoles();
        Mockito.verify(allowedRoles, Mockito.never()).watchIssueRoles();
    }

    @Test
    @DisplayName("watchIssue: задача не найдена → NOT_FOUND")
    void watchIssue_issueNotFound() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.watchIssue(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, null))
                .expectErrorMatches(e -> e instanceof DomainException
                        && ((DomainException) e).getStatus() == DomainStatus.NOT_FOUND)
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("watchIssue: ошибка роли → PERMISSION_DENIED пробрасывается")
    void watchIssue_permissionDenied() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, watchRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(service.watchIssue(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, null))
                .expectErrorMatches(e -> e instanceof DomainException
                        && ((DomainException) e).getStatus() == DomainStatus.PERMISSION_DENIED)
                .verify();

        Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("unwatchIssue: должен вернуть removed=true и count")
    void unwatchIssue_shouldSucceed() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, watchRoles))
                .thenReturn(Mono.empty());
        Mockito.when(executor.executeUnwatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, ACTOR_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(true));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(2L));

        StepVerifier.create(service.unwatchIssue(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, null))
                .assertNext(result -> {
                    Assertions.assertThat(result.removed()).isTrue();
                    Assertions.assertThat(result.watchersCount()).isEqualTo(2L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("unwatchIssue: отписка другого пользователя требует manageWatchersRoles")
    void unwatchIssue_forTarget_shouldUseManageRoles() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, manageRoles))
                .thenReturn(Mono.empty());
        Mockito.when(executor.executeUnwatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, TARGET_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(true));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.unwatchIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, TARGET_USER_ID))
                .assertNext(result -> {
                    Assertions.assertThat(result.removed()).isTrue();
                    Assertions.assertThat(result.watchersCount()).isZero();
                })
                .verifyComplete();

        Mockito.verify(allowedRoles).manageWatchersRoles();
        Mockito.verify(allowedRoles, Mockito.never()).watchIssueRoles();
        Mockito.verify(executor).executeUnwatch(
                REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, TARGET_USER_ID, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("listIssueWatchers: должен вернуть PageResult с items и totalCount")
    void listIssueWatchers_shouldReturnPage() {
        IssueWatcher second = watcher.toBuilder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000006"))
                .userId(TARGET_USER_ID)
                .build();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, listRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(2L));
        Mockito.when(issueWatcherRepository.findByIssueId(ISSUE_ID, 10, 0L))
                .thenReturn(Flux.fromIterable(List.of(watcher, second)));

        StepVerifier.create(service.listIssueWatchers(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, 0, 10))
                .assertNext(page -> {
                    Assertions.assertThat(page.items()).hasSize(2);
                    Assertions.assertThat(page.totalCount()).isEqualTo(2L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssueWatchers: page=1, size=1 возвращает страницу из БД с LIMIT/OFFSET")
    void listIssueWatchers_shouldApplyLimitAndOffset() {
        IssueWatcher second = watcher.toBuilder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000006"))
                .userId(TARGET_USER_ID)
                .build();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, listRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(2L));
        Mockito.when(issueWatcherRepository.findByIssueId(ISSUE_ID, 1, 1L))
                .thenReturn(Flux.just(second));

        StepVerifier.create(service.listIssueWatchers(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, 1, 1))
                .assertNext(page -> {
                    Assertions.assertThat(page.items()).containsExactly(second);
                    Assertions.assertThat(page.totalCount()).isEqualTo(2L);
                })
                .verifyComplete();

        Mockito.verify(issueWatcherRepository).findByIssueId(ISSUE_ID, 1, 1L);
    }

    @Test
    @DisplayName("listIssueWatchers: пустой список → items=[] и totalCount=0")
    void listIssueWatchers_shouldReturnEmptyPage() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, listRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(0L));
        Mockito.when(issueWatcherRepository.findByIssueId(ISSUE_ID, 10, 0L)).thenReturn(Flux.empty());

        StepVerifier.create(service.listIssueWatchers(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, 0, 10))
                .assertNext(page -> {
                    Assertions.assertThat(page.items()).isEmpty();
                    Assertions.assertThat(page.totalCount()).isZero();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssueWatchers: ошибка роли → PERMISSION_DENIED")
    void listIssueWatchers_permissionDenied() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, listRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(service.listIssueWatchers(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, 0, 10))
                .expectErrorMatches(e -> e instanceof DomainException
                        && ((DomainException) e).getStatus() == DomainStatus.PERMISSION_DENIED)
                .verify();

        Mockito.verify(issueWatcherRepository, Mockito.never())
                .findByIssueId(Mockito.any(), Mockito.anyInt(), Mockito.anyLong());
    }

    @Test
    @DisplayName("getIssueWatchState: должен вернуть watchedByMe и count")
    void getIssueWatchState_shouldSucceed() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, listRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueWatcherRepository.existsByIssueIdAndUserId(ISSUE_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(true));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(5L));

        StepVerifier.create(service.getIssueWatchState(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID))
                .assertNext(state -> {
                    Assertions.assertThat(state.watchedByMe()).isTrue();
                    Assertions.assertThat(state.watchersCount()).isEqualTo(5L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getWatchState: enrichment-метод без requestId/nodeId и без проверки ролей")
    void getWatchState_shouldReadWithoutRoleCheck() {
        Mockito.when(issueWatcherRepository.existsByIssueIdAndUserId(ISSUE_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(false));
        Mockito.when(issueWatcherRepository.countByIssueId(ISSUE_ID)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.getWatchState(ISSUE_ID, ACTOR_USER_ID))
                .assertNext(state -> {
                    Assertions.assertThat(state.watchedByMe()).isFalse();
                    Assertions.assertThat(state.watchersCount()).isEqualTo(1L);
                })
                .verifyComplete();

        Mockito.verifyNoInteractions(projectRoleChecker, issueRepository);
    }
}
