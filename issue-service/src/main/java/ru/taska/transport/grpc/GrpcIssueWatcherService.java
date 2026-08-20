package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.GetIssueWatchStateRequest;
import ru.taska.api.issue.v1.GetIssueWatchStateResponse;
import ru.taska.api.issue.v1.ListIssueWatchersRequest;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.UnwatchIssueRequest;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.api.issue.v1.WatchIssueRequest;
import ru.taska.api.issue.v1.WatchIssueResponse;
import ru.taska.mapper.IssueWatcherMapper;
import ru.taska.service.IssueWatcherService;
import ru.taska.transport.grpc.dto.IssueActorContext;
import ru.taska.transport.grpc.dto.IssueActorTargetContext;
import ru.taska.transport.grpc.logging.GrpcIssueLogging;
import validator.GrpcRequestValidators;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueWatcherService {

    private final IssueWatcherService issueWatcherService;
    private final IssueWatcherMapper issueWatcherMapper;

    @TrackMetrics(counter = IssueGrpcMetrics.WATCH_ISSUE_COUNTER,
            timer = IssueGrpcMetrics.WATCH_ISSUE_TIMER)
    public Mono<WatchIssueResponse> watchIssue(Mono<WatchIssueRequest> request) {
        return request
                .flatMap(req -> validateIssueActorTarget(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getActorUserId(),
                                req.getBody().hasTargetUserId() ? req.getBody().getTargetUserId() : null,
                                "watchIssue"
                        )
                        .flatMap(ctx -> {
                            String requestId = ctx.requestId();
                            String nodeId = ctx.nodeId();
                            UUID issueId = ctx.issueId();
                            UUID actorUserId = ctx.actorUserId();
                            UUID targetUserId = ctx.targetUserId();

                            log.info("[{}][{}] watchIssue: issueId={}, actorUserId={}, targetUserId={}",
                                    requestId, nodeId, issueId, actorUserId, targetUserId);

                            return issueWatcherService.watchIssue(
                                            requestId, nodeId, issueId, actorUserId, targetUserId
                                    )
                                    .map(issueWatcherMapper::toWatchIssueResponse)
                                    .doOnSuccess(response -> log.info(
                                            "[{}][{}] watchIssue: successfully watched, issueId={}, watchersCount={}",
                                            requestId, nodeId, issueId, response.getWatchersCount()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(requestId, nodeId, "watchIssue"));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("watchIssue"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.UNWATCH_ISSUE_COUNTER,
            timer = IssueGrpcMetrics.UNWATCH_ISSUE_TIMER)
    public Mono<UnwatchIssueResponse> unwatchIssue(Mono<UnwatchIssueRequest> request) {
        return request
                .flatMap(req -> validateIssueActorTarget(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getActorUserId(),
                                req.getBody().hasTargetUserId() ? req.getBody().getTargetUserId() : null,
                                "unwatchIssue"
                        )
                        .flatMap(ctx -> {
                            String requestId = ctx.requestId();
                            String nodeId = ctx.nodeId();
                            UUID issueId = ctx.issueId();
                            UUID actorUserId = ctx.actorUserId();
                            UUID targetUserId = ctx.targetUserId();

                            log.info("[{}][{}] unwatchIssue: issueId={}, actorUserId={}, targetUserId={}",
                                    requestId, nodeId, issueId, actorUserId, targetUserId);

                            return issueWatcherService.unwatchIssue(
                                            requestId, nodeId, issueId, actorUserId, targetUserId
                                    )
                                    .map(result -> issueWatcherMapper.toUnwatchIssueResponse(issueId, result))
                                    .doOnSuccess(response -> log.info(
                                            "[{}][{}] unwatchIssue: issueId={}, removed={}, watchersCount={}",
                                            requestId, nodeId, issueId,
                                            response.getRemoved(), response.getWatchersCount()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(requestId, nodeId, "unwatchIssue"));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("unwatchIssue"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.LIST_ISSUE_WATCHERS_COUNTER,
            timer = IssueGrpcMetrics.LIST_ISSUE_WATCHERS_TIMER)
    public Mono<ListIssueWatchersResponse> listIssueWatchers(Mono<ListIssueWatchersRequest> request) {
        return request
                .flatMap(req -> validateIssueActor(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getActorUserId(),
                                "listIssueWatchers"
                        )
                        .flatMap(ctx -> {
                            String requestId = ctx.requestId();
                            String nodeId = ctx.nodeId();
                            UUID issueId = ctx.issueId();
                            UUID actorUserId = ctx.actorUserId();

                            Integer page = req.getBody().hasPage() ? req.getBody().getPage() : null;
                            Integer pageSize = req.getBody().hasPageSize() ? req.getBody().getPageSize() : null;

                            log.info("[{}][{}] listIssueWatchers: issueId={}, actorUserId={}, page={}, pageSize={}",
                                    requestId, nodeId, issueId, actorUserId, page, pageSize);

                            return issueWatcherService.listIssueWatchers(
                                            requestId, nodeId, issueId, actorUserId, page, pageSize
                                    )
                                    .map(issueWatcherMapper::toListWatchersResponse)
                                    .doOnSuccess(response -> log.info(
                                            "[{}][{}] listIssueWatchers: successfully found {} watchers, issueId={}",
                                            requestId, nodeId, response.getTotalCount(), issueId
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(requestId, nodeId, "listIssueWatchers"));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueWatchers"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.GET_ISSUE_WATCH_STATE_COUNTER,
            timer = IssueGrpcMetrics.GET_ISSUE_WATCH_STATE_TIMER)
    public Mono<GetIssueWatchStateResponse> getIssueWatchState(Mono<GetIssueWatchStateRequest> request) {
        return request
                .flatMap(req -> validateIssueActor(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getActorUserId(),
                                "getIssueWatchState"
                        )
                        .flatMap(ctx -> {
                            String requestId = ctx.requestId();
                            String nodeId = ctx.nodeId();
                            UUID issueId = ctx.issueId();
                            UUID actorUserId = ctx.actorUserId();

                            log.info("[{}][{}] getIssueWatchState: issueId={}, actorUserId={}",
                                    requestId, nodeId, issueId, actorUserId);

                            return issueWatcherService.getIssueWatchState(requestId, nodeId, issueId, actorUserId)
                                    .map(issueWatcherMapper::toWatchStateResponse)
                                    .doOnSuccess(response -> log.info(
                                            "[{}][{}] getIssueWatchState: issueId={}, watchedByMe={}, watchersCount={}",
                                            requestId, nodeId, issueId,
                                            response.getWatchedByMe(), response.getWatchersCount()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(requestId, nodeId, "getIssueWatchState"));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("getIssueWatchState"));
    }

    private Mono<IssueActorContext> validateIssueActor(
            String requestId,
            String nodeId,
            String issueId,
            String actorUserId,
            String operation
    ) {
        return Mono.zip(
                        GrpcRequestValidators.requireHeaderRequestId(requestId),
                        GrpcRequestValidators.requireHeaderNodeId(nodeId),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(issueId, "body.issueId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(actorUserId, "body.actorUserId")
                )
                .map(t -> new IssueActorContext(t.getT1(), t.getT2(), t.getT3(), t.getT4()))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
    }

    private Mono<IssueActorTargetContext> validateIssueActorTarget(
            String requestId,
            String nodeId,
            String issueId,
            String actorUserId,
            String targetUserId,
            String operation
    ) {
        Mono<Optional<UUID>> targetUserIdMono = targetUserId == null
                ? Mono.just(Optional.empty())
                : GrpcRequestValidators.parseUuidOrInvalidArgument(targetUserId, "body.targetUserId").map(Optional::of);

        return Mono.zip(
                        GrpcRequestValidators.requireHeaderRequestId(requestId),
                        GrpcRequestValidators.requireHeaderNodeId(nodeId),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(issueId, "body.issueId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(actorUserId, "body.actorUserId"),
                        targetUserIdMono
                )
                .map(t -> new IssueActorTargetContext(
                        t.getT1(),
                        t.getT2(),
                        t.getT3(),
                        t.getT4(),
                        t.getT5().orElse(null)
                ))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
    }
}
