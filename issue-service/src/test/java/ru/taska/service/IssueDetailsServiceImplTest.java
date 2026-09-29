package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.dto.UserSummary;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.service.attachment.AttachmentService;
import ru.taska.service.impl.IssueAccessGuard;
import ru.taska.service.impl.IssueDetailsServiceImpl;
import ru.taska.service.link.IssueLinkService;
import ru.taska.transport.grpc.profile.GrpcAuthServiceClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@ExtendWith(MockitoExtension.class)
class IssueDetailsServiceImplTest {

    private static final String REQUEST_ID = "req-1";
    private static final String NODE_ID = "node-1";

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private LabelService labelService;
    @Mock
    private IssueWatcherService watcherService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private IssueLinkService issueLinkService;
    @Mock
    private IssueHistoryService issueHistoryService;
    @Mock
    private GrpcAuthServiceClient grpcAuthServiceClient;
    @Mock
    private IssueAccessGuard issueAccessGuard;

    private IssueDetailsServiceImpl service;

    private UUID issueId;
    private UUID actorUserId;
    private UUID reporterId;

    @BeforeEach
    void setUp() {
        service = new IssueDetailsServiceImpl(
                issueRepository,
                labelService,
                watcherService,
                attachmentService,
                issueLinkService,
                issueHistoryService,
                grpcAuthServiceClient,
                issueAccessGuard
        );

        issueId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        reporterId = UUID.randomUUID();
    }

    // ---------- helpers для сборки доменных объектов ----------

    private Issue buildIssue(UUID assigneeId, UUID reporterId) {
        return Issue.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .issueNumber(1)
                .issueKey("TEST-1")
                .issueType(IssueType.TASK)
                .summary("Test issue")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .version(1)
                .build();
    }

    private IssueCoreDetails buildCoreDetails(Issue issue) {
        return new IssueCoreDetails(issue, 3L, false);
    }

    private IssueWatcher buildWatcher(UUID userId) {
        return IssueWatcher.builder()
                .id(UUID.randomUUID())
                .issueId(issueId)
                .userId(userId)
                .build();
    }

    private AttachmentDto buildAttachment(UUID uploadedBy) {
        IssueAttachment issueAttachment = IssueAttachment.builder()
                .id(UUID.randomUUID())
                .issueId(issueId)
                .fileName("file.txt")
                .uploadedBy(uploadedBy)
                .build();

        return new AttachmentDto(issueAttachment, "http://test.test/test");
    }

    private UserSummary summaryOf(UUID id) {
        return new UserSummary(id, "user-" + id, null);
    }

    /**
     * Настраивает guard так, будто задача найдена и доступ разрешён —
     * просто пропускает core дальше по цепочке, как это делает реальный
     * {@code issueAccessGuard.verifyReadAccess(...)} в happy-path.
     */
    @SuppressWarnings("unchecked")
    private void stubAccessGranted(IssueCoreDetails core) {
        Mockito.when(issueAccessGuard.verifyReadAccess(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueId), Mockito.eq(actorUserId),
                        ArgumentMatchers.any(Mono.class), ArgumentMatchers.any(Function.class)))
                .thenReturn(Mono.just(core));

        // сам issueRepository.findIssueCoreDetails по-прежнему вызывается сервисом —
        // именно этот Mono передаётся первым аргументом в guard
        Mockito.when(issueRepository.findIssueCoreDetails(issueId, actorUserId))
                .thenReturn(Mono.just(core));
    }

    /** Настраивает guard так, будто он вернул ошибку (NOT_FOUND/FORBIDDEN/др.), не заходя в детали. */
    private void stubAccessDenied(Throwable error) {
        Mockito.when(issueAccessGuard.verifyReadAccess(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueId), Mockito.eq(actorUserId),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Mono.error(error));
    }

    /** Стабит "побочные" источники (labels/links/history) пустыми потоками. */
    private void stubUnrelatedSourcesEmpty() {
        Mockito.lenient()
                .when(labelService.getLabels(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), ArgumentMatchers.any()))
                .thenReturn(Flux.empty());
        Mockito.lenient()
                .when(issueLinkService.listIssueLinksDetails(REQUEST_ID, NODE_ID, issueId, actorUserId))
                .thenReturn(Flux.empty());
        Mockito.lenient()
                .when(issueHistoryService.getHistory(REQUEST_ID, NODE_ID, issueId))
                .thenReturn(Flux.empty());
    }

    // ---------- позитивные сценарии ----------

    @Nested
    @DisplayName("Позитивные сценарии сборки агрегата")
    class PositiveCases {

        @Test
        @DisplayName("Собирает полный агрегат: assignee, reporter, watcher и автор вложения резолвятся в профили")
        void shouldBuildFullAggregateWhenAllDataPresent() {
            UUID assigneeId = UUID.randomUUID();
            UUID watcherId = UUID.randomUUID();
            UUID attachmentAuthorId = UUID.randomUUID();

            IssueCoreDetails core = buildCoreDetails(buildIssue(assigneeId, reporterId));
            IssueWatcher watcher = buildWatcher(watcherId);
            AttachmentDto attachment = buildAttachment(attachmentAuthorId);

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.just(watcher));
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.just(attachment));

            Set<UUID> expectedUserIds = Set.of(assigneeId, reporterId, watcherId, attachmentAuthorId);
            Map<UUID, UserSummary> profiles = Map.of(
                    assigneeId, summaryOf(assigneeId),
                    reporterId, summaryOf(reporterId),
                    watcherId, summaryOf(watcherId),
                    attachmentAuthorId, summaryOf(attachmentAuthorId)
            );

            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(expectedUserIds), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(profiles));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> {
                        Assertions.assertEquals(core, aggregate.issueCore());
                        Assertions.assertEquals(1, aggregate.watchers().items().size());
                        Assertions.assertEquals(1, aggregate.attachments().items().size());
                        Assertions.assertEquals(4, aggregate.userProfiles().size());
                        Assertions.assertEquals(profiles, aggregate.userProfiles());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Резолвит только reporter, если задача не назначена (assignee отсутствует)")
        void shouldResolveOnlyReporterWhenIssueIsUnassigned() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId)), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(Map.of(reporterId, summaryOf(reporterId))));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> {
                        Assertions.assertEquals(1, aggregate.userProfiles().size());
                        Assertions.assertTrue(aggregate.userProfiles().containsKey(reporterId));
                    })
                    .verifyComplete();

            Mockito.verify(grpcAuthServiceClient)
                    .getUserProfiles(Set.of(reporterId), REQUEST_ID, NODE_ID);
        }

        @Test
        @DisplayName("Дедуплицирует id, когда reporter одновременно является наблюдателем и автором вложения")
        void shouldDeduplicateUserIdsAcrossRoles() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));
            IssueWatcher watcherSameAsReporter = buildWatcher(reporterId);
            AttachmentDto attachmentSameAsReporter = buildAttachment(reporterId);

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.just(watcherSameAsReporter));
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.just(attachmentSameAsReporter));
            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId)), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(Map.of(reporterId, summaryOf(reporterId))));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> Assertions.assertEquals(1, aggregate.userProfiles().size()))
                    .verifyComplete();

            ArgumentCaptor<Set<UUID>> userIdsCaptor = ArgumentCaptor.forClass(Set.class);
            Mockito.verify(grpcAuthServiceClient)
                    .getUserProfiles(userIdsCaptor.capture(), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID));
            Assertions.assertEquals(1, userIdsCaptor.getValue().size());
        }

        @Test
        @DisplayName("Собирает агрегат с пустыми списками меток/связей/истории/вложений/наблюдателей")
        void shouldBuildAggregateWithEmptyCollateralLists() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId)), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(Map.of(reporterId, summaryOf(reporterId))));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> {
                        Assertions.assertEquals(List.of(), aggregate.labels().items());
                        Assertions.assertEquals(List.of(), aggregate.watchers().items());
                        Assertions.assertEquals(List.of(), aggregate.attachments().items());
                        Assertions.assertEquals(List.of(), aggregate.links().items());
                        Assertions.assertEquals(List.of(), aggregate.history());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Передаёт корректные requestId/nodeId/issueId/actorUserId во все зависимые сервисы")
        void shouldPropagateCorrectArgumentsToAllDependencies() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId)), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(Map.of(reporterId, summaryOf(reporterId))));

            StepVerifier.create(service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .expectNextCount(1)
                    .verifyComplete();

            Mockito.verify(watcherService).listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId);
            Mockito.verify(attachmentService).listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId);
            Mockito.verify(issueLinkService).listIssueLinksDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);
            Mockito.verify(issueHistoryService).getHistory(REQUEST_ID, NODE_ID, issueId);
        }
    }

    // ---------- граничные и ошибочные сценарии ----------

    @Nested
    @DisplayName("Граничные сценарии и обработка ошибок")
    class EdgeCases {

        @Test
        @DisplayName("Пробрасывает NOT_FOUND и не запрашивает collateral-источники, если задача не найдена")
        void shouldPropagateNotFoundAndSkipCollateralSourcesWhenIssueMissing() {
            DomainException notFound = new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId);
            stubAccessDenied(notFound);

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == notFound)
                    .verify();

            Mockito.verifyNoInteractions(
                    labelService, watcherService, attachmentService,
                    issueLinkService, issueHistoryService, grpcAuthServiceClient
            );
        }

        @Test
        @DisplayName("Пробрасывает PERMISSION_DENIED и не запрашивает collateral-источники, если роль пользователя не разрешена")
        void shouldPropagateForbiddenAndSkipCollateralSourcesWhenAccessDenied() {
            DomainException permissionDenied = new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied");
            stubAccessDenied(permissionDenied);

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == permissionDenied)
                    .verify();

            Mockito.verifyNoInteractions(
                    labelService, watcherService, attachmentService,
                    issueLinkService, issueHistoryService, grpcAuthServiceClient
            );
        }

        @Test
        @DisplayName("Собирает и возвращает данные, если один из collateral-источников (watchers) завершился с ошибкой")
        void shouldReturnAggregateWhenWatchersSourceFails() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));
            RuntimeException watcherFailure = new RuntimeException("watcher-service unavailable");

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.error(watcherFailure));
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(grpcAuthServiceClient.getUserProfiles(Mockito.anySet(), Mockito.any(), Mockito.anyString()))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(res -> {
                        Assertions.assertFalse(res.watchers().available());
                        Assertions.assertTrue(res.attachments().items().isEmpty());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Собирает и возвращает данные, если один из collateral-источников (attachments) завершился с ошибкой")
        void shouldReturnAggregateWhenAttachmentsSourceFails() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));
            RuntimeException attachmentFailure = new RuntimeException("attachment-service unavailable");

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.error(attachmentFailure));
            Mockito.when(grpcAuthServiceClient.getUserProfiles(Mockito.anySet(), Mockito.any(), Mockito.anyString()))
                    .thenReturn(Mono.just(Collections.emptyMap()));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(res -> {
                        Assertions.assertFalse(res.attachments().available());
                        Assertions.assertTrue(res.watchers().items().isEmpty());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Продолжает сборку aggregate, если сервис профилей недоступен")
        void shouldContinueWhenProfileResolutionFails() {
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));
            RuntimeException authFailure = new RuntimeException("auth service unavailable");

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();

            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());

            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());

            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId)),
                            Mockito.eq(REQUEST_ID),
                            Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.error(authFailure));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> {
                        Assertions.assertNotNull(aggregate);
                        Assertions.assertSame(core, aggregate.issueCore());
                        Assertions.assertTrue(aggregate.userProfiles().isEmpty());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Собирает агрегат с частичной картой профилей, если auth-сервис вернул не всех пользователей")
        void shouldBuildAggregateWithPartialProfileMap() {
            UUID watcherId = UUID.randomUUID();
            IssueCoreDetails core = buildCoreDetails(buildIssue(null, reporterId));
            IssueWatcher watcher = buildWatcher(watcherId);

            stubAccessGranted(core);
            stubUnrelatedSourcesEmpty();
            Mockito.when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.just(watcher));
            Mockito.when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                    .thenReturn(Flux.empty());

            Mockito.when(grpcAuthServiceClient.getUserProfiles(
                            Mockito.eq(Set.of(reporterId, watcherId)), Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID)))
                    .thenReturn(Mono.just(Map.of(reporterId, summaryOf(reporterId))));

            Mono<IssueDetailsAggregate> result =
                    service.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

            StepVerifier.create(result)
                    .assertNext(aggregate -> {
                        Assertions.assertEquals(1, aggregate.userProfiles().size());
                        Assertions.assertFalse(aggregate.userProfiles().containsKey(watcherId));
                        Assertions.assertEquals(1, aggregate.watchers().items().size());
                    })
                    .verifyComplete();
        }
    }
}
