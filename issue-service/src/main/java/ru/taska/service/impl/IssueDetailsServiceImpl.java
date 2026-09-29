package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.aggregate.IssueDetailsSources;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetail;
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
            String requestId, String nodeId, UUID issueId, UUID actorUserId
    ) {
        log.info("[{}][{}] getIssueDetails: issueId={}, actorUserId={}",
                requestId, nodeId, issueId, actorUserId);

        return verifyAccessAndFetchCore(requestId, nodeId, issueId, actorUserId)
                .flatMap(core -> fetchCollateralSources(requestId, nodeId, issueId, actorUserId, core))
                .flatMap(sources -> resolveProfiles(sources.extractUserIds(), requestId, nodeId)
                        .map(sources::withProfiles));
    }

    /**
     * Проверяет существование задачи и доступ actorUserId по роли, отдаёт core-данные.
     * Fail-fast: если задача не найдена или доступ запрещён — остальные 5 источников
     * (labels/watchers/attachments/links/history) вообще не запрашиваются.
     */
    private Mono<IssueCoreDetails> verifyAccessAndFetchCore(
            String requestId, String nodeId, UUID issueId, UUID actorUserId
    ) {
        return issueAccessGuard.verifyReadAccess(
                requestId, nodeId, issueId, actorUserId,
                issueRepository.findIssueCoreDetails(issueId, actorUserId),
                details -> details.issue().getProjectId()
        );
    }

    /**
     * Параллельно загружает сопутствующие данные задачи (не влияющие на доступ)
     * и собирает их вместе с уже провалидированным core в {@link IssueDetailsSources}.
     */
    private Mono<IssueDetailsSources> fetchCollateralSources(
            String requestId, String nodeId, UUID issueId, UUID actorUserId, IssueCoreDetails core
    ) {
        LabelCommands.ListIssueLabelsRequestDto labelsRequest =
                new LabelCommands.ListIssueLabelsRequestDto(issueId, actorUserId);

        Mono<FetchResult<LabelResponses.ProjectLabelInfo>> labels = fetchResilient(
                labelService.getLabels(requestId, nodeId, labelsRequest),
                requestId, nodeId, issueId, "labels");

        Mono<FetchResult<IssueWatcher>> watchers = fetchResilient(
                watcherService.listIssueWatchers(requestId, nodeId, issueId, actorUserId),
                requestId, nodeId, issueId, "watchers");

        Mono<FetchResult<AttachmentDto>> attachments = fetchResilient(
                attachmentService.listAttachments(requestId, nodeId, issueId, actorUserId),
                requestId, nodeId, issueId, "attachments");

        Mono<FetchResult<IssueLinkDetail>> links = fetchResilient(
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

    private <T> Mono<FetchResult<T>> fetchResilient(
            Flux<T> source, String requestId, String nodeId, UUID issueId, String sourceName
    ) {
        return source.collectList()
                .map(FetchResult::<T>ok)
                .doOnError(logSourceError(requestId, nodeId, issueId, sourceName))
                .onErrorReturn(FetchResult.failed());
    }

    private Mono<Map<UUID, UserSummary>> resolveProfiles(
            Set<UUID> userIds, String requestId, String nodeId
    ) {
        if (userIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return grpcAuthServiceClient.getUserProfiles(userIds, requestId, nodeId)
                .onErrorReturn(Collections.emptyMap());
    }

    private Consumer<Throwable> logSourceError(
            String requestId, String nodeId, UUID issueId, String sourceName
    ) {
        return e -> log.error(
                "[{}][{}] getIssueDetails: failed to load {}, issueId={}, error={}",
                requestId, nodeId, sourceName, issueId, e.getMessage(), e);
    }
}
