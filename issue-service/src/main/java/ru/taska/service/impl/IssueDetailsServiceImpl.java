package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.dto.AttachmentDto;
import ru.taska.domain.aggregate.IssueDetailsSources;
import ru.taska.domain.entity.IssueHistory;
import ru.taska.domain.entity.IssueWatcher;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetails;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelCommands;
import ru.taska.domain.dto.labels.LabelResponses;
import ru.taska.repository.IssueRepository;
import ru.taska.domain.util.FetchResult;
import ru.taska.service.IssueDetailsService;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.IssueWatcherService;
import ru.taska.service.LabelService;
import ru.taska.service.attachment.AttachmentService;
import ru.taska.service.link.IssueLinkService;
import ru.taska.transport.grpc.profile.GrpcAuthServiceClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueDetailsServiceImpl implements IssueDetailsService {
    private final IssueRepository issueRepository;
    private final LabelService labelService;
    private final IssueWatcherService watcherService;
    private final AttachmentService attachmentService;
    private final IssueLinkService issueLinkService;
    private final IssueHistoryService issueHistoryService;
    private final GrpcAuthServiceClient grpcAuthServiceClient;
    private final IssueAccessGuard issueAccessGuard;

    @Override
    public Mono<IssueDetailsAggregate> getIssueDetails(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        log.info("[{}][{}] getIssueDetails: issueId={}, actorUserId={}",
                requestId, nodeId, issueId, actorUserId);

        return verifyAccessAndFetchCoreDetails(requestId, nodeId, issueId, actorUserId)
                .flatMap(core -> fetchIssueDetailsSources(requestId, nodeId, issueId, actorUserId, core))
                .flatMap(sources -> getProfiles(sources.extractUserIds(), requestId, nodeId)
                        .map(profiles -> new IssueDetailsAggregate(sources, profiles)));
    }

    /**
     * Проверяет существование задачи и доступ {@code actorUserId} по роли
     * и возвращает основные данные задачи.
     *
     * <p>При отсутствии задачи или запрете доступа выполнение завершается с ошибкой,
     * остальные источники данных не запрашиваются.</p>
     */
    private Mono<IssueCoreDetails> verifyAccessAndFetchCoreDetails(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return issueAccessGuard.verifyReadAccess(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                issueRepository.findIssueCoreDetails(issueId, actorUserId),
                details -> details.issue().getProjectId()
        );
    }

    /**
     * Параллельно загружает сопутствующие данные задачи (не влияющие на доступ)
     * и собирает их вместе с уже проверенными основными данными задачи в {@link IssueDetailsSources}.
     */
    private Mono<IssueDetailsSources> fetchIssueDetailsSources(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            IssueCoreDetails core
    ) {
        LabelCommands.ListIssueLabelsRequestDto labelsRequest =
                new LabelCommands.ListIssueLabelsRequestDto(issueId, actorUserId);

        Mono<FetchResult<LabelResponses.ProjectLabelInfo>> labels = fetchWithFallback(
                labelService.getLabels(requestId, nodeId, labelsRequest),
                requestId, nodeId, issueId, "labels");

        Mono<FetchResult<IssueWatcher>> watchers = fetchWithFallback(
                watcherService.listIssueWatchers(requestId, nodeId, issueId, actorUserId),
                requestId, nodeId, issueId, "watchers");

        Mono<FetchResult<AttachmentDto>> attachments = fetchWithFallback(
                attachmentService.listAttachments(requestId, nodeId, issueId, actorUserId),
                requestId, nodeId, issueId, "attachments");

        Mono<FetchResult<IssueLinkDetails>> links = fetchWithFallback(
                issueLinkService.listIssueLinksDetails(requestId, nodeId, issueId, actorUserId),
                requestId, nodeId, issueId, "links");

        Mono<List<IssueHistory>> history = issueHistoryService.getHistory(requestId, nodeId, issueId)
                .collectList()
                .doOnError(logSourceError(requestId, nodeId, issueId, "history"));

        return Mono.zip(labels, watchers, attachments, links, history)
                .map(t -> new IssueDetailsSources(
                        core, t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5())
                );

    }

    /**
     * Загружает данные из источника и преобразует результат в {@link FetchResult}.
     *
     * <p>При успешной загрузке все элементы собираются в список и возвращаются
     * как успешный результат. При ошибке она логируется, а вместо неё возвращается
     * {@link FetchResult#failed()}, что позволяет продолжить обработку остальных
     * источников данных.</p>
     *
     * @param source источник данных
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла сервиса
     * @param issueId идентификатор задачи
     * @param sourceName название источника данных для логирования
     * @param <T> тип элементов источника
     * @return результат загрузки данных
     */
    private <T> Mono<FetchResult<T>> fetchWithFallback(
            Flux<T> source,
            String requestId,
            String nodeId,
            UUID issueId,
            String sourceName
    ) {
        return source.collectList()
                .map(FetchResult::<T>ok)
                .doOnError(logSourceError(requestId, nodeId, issueId, sourceName))
                .onErrorReturn(FetchResult.failed());
    }

    private Mono<Map<UUID, UserSummary>> getProfiles(
            Set<UUID> userIds,
            String requestId,
            String nodeId
    ) {
        if (userIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return grpcAuthServiceClient.getUserProfiles(userIds, requestId, nodeId)
                .onErrorReturn(Collections.emptyMap());
    }

    private Consumer<Throwable> logSourceError(
            String requestId,
            String nodeId,
            UUID issueId,
            String sourceName
    ) {
        return e -> log.error(
                "[{}][{}] getIssueDetails: failed to load {}, issueId={}, error={}",
                requestId, nodeId, sourceName, issueId, e.getMessage(), e);
    }
}
