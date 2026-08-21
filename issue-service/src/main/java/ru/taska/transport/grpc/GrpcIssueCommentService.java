package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.AddIssueCommentRequest;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueCommentRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.ListIssueCommentsRequest;
import ru.taska.api.issue.v1.ListIssueCommentsResponse;
import ru.taska.api.issue.v1.UpdateIssueCommentRequest;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.mapper.CommentMapper;
import ru.taska.service.CommentService;
import ru.taska.transport.grpc.dto.AddIssueCommentContext;
import ru.taska.transport.grpc.dto.DeleteIssueCommentContext;
import ru.taska.transport.grpc.dto.IssueActorContext;
import ru.taska.transport.grpc.dto.UpdateIssueCommentContext;
import ru.taska.transport.grpc.logging.GrpcIssueLogging;
import validator.GrpcRequestValidators;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueCommentService {

    private final CommentService commentService;
    private final CommentMapper commentMapper;

    @TrackMetrics(counter = IssueGrpcMetrics.ADD_ISSUE_COMMENT_COUNTER,
            timer = IssueGrpcMetrics.ADD_ISSUE_COMMENT_TIMER)
    public Mono<AddIssueCommentResponse> addIssueComment(Mono<AddIssueCommentRequest> request) {
        return request
                .flatMap(req -> validateAddComment(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getAuthorUserId(),
                                req.getBody().getBody(),
                                "addIssueComment"
                        )
                        .flatMap(ctx -> {
                            log.info("[{}][{}] addIssueComment: issueId={}, authorUserId={}",
                                    ctx.requestId(), ctx.nodeId(), ctx.issueId(), ctx.authorUserId());

                            return commentService.addComment(
                                            ctx.requestId(), ctx.nodeId(),
                                            ctx.issueId(), ctx.authorUserId(), ctx.body()
                                    )
                                    .doOnSuccess(comment -> log.info(
                                            "[{}][{}] addIssueComment: successfully added, commentId={}",
                                            ctx.requestId(), ctx.nodeId(), comment.getId()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(
                                            ctx.requestId(), ctx.nodeId(), "addIssueComment"
                                    ));
                        }))
                .map(commentMapper::toAddCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueComment"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.UPDATE_ISSUE_COMMENT_COUNTER,
            timer = IssueGrpcMetrics.UPDATE_ISSUE_COMMENT_TIMER)
    public Mono<UpdateIssueCommentResponse> updateIssueComment(Mono<UpdateIssueCommentRequest> request) {
        return request
                .flatMap(req -> validateUpdateComment(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getCommentId(),
                                req.getBody().getActorUserId(),
                                req.getBody().getBody(),
                                "updateIssueComment"
                        )
                        .flatMap(ctx -> {
                            log.info("[{}][{}] updateIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    ctx.requestId(), ctx.nodeId(),
                                    ctx.issueId(), ctx.commentId(), ctx.actorUserId());

                            return commentService.updateComment(
                                            ctx.requestId(), ctx.nodeId(),
                                            ctx.issueId(), ctx.commentId(), ctx.actorUserId(), ctx.body()
                                    )
                                    .doOnSuccess(comment -> log.info(
                                            "[{}][{}] updateIssueComment: successfully updated, commentId={}",
                                            ctx.requestId(), ctx.nodeId(), comment.getId()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(
                                            ctx.requestId(), ctx.nodeId(), "updateIssueComment"
                                    ));
                        }))
                .map(commentMapper::toUpdateCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssueComment"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.DELETE_ISSUE_COMMENT_COUNTER,
            timer = IssueGrpcMetrics.DELETE_ISSUE_COMMENT_TIMER)
    public Mono<DeleteIssueCommentResponse> deleteIssueComment(Mono<DeleteIssueCommentRequest> request) {
        return request
                .flatMap(req -> validateDeleteComment(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getCommentId(),
                                req.getBody().getActorUserId(),
                                "deleteIssueComment"
                        )
                        .flatMap(ctx -> {
                            log.info("[{}][{}] deleteIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    ctx.requestId(), ctx.nodeId(),
                                    ctx.issueId(), ctx.commentId(), ctx.actorUserId());

                            return commentService.deleteComment(
                                            ctx.requestId(), ctx.nodeId(),
                                            ctx.issueId(), ctx.commentId(), ctx.actorUserId()
                                    )
                                    .doOnSuccess(comment -> log.info(
                                            "[{}][{}] deleteIssueComment: successfully deleted, commentId={}",
                                            ctx.requestId(), ctx.nodeId(), comment.getId()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(
                                            ctx.requestId(), ctx.nodeId(), "deleteIssueComment"
                                    ));
                        }))
                .map(commentMapper::toDeleteCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueComment"));
    }

    @TrackMetrics(counter = IssueGrpcMetrics.LIST_ISSUE_COMMENTS_COUNTER,
            timer = IssueGrpcMetrics.LIST_ISSUE_COMMENTS_TIMER)
    public Mono<ListIssueCommentsResponse> listIssueComments(Mono<ListIssueCommentsRequest> request) {
        return request
                .flatMap(req -> validateIssueActor(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                req.getBody().getIssueId(),
                                req.getBody().getActorUserId(),
                                "listIssueComments"
                        )
                        .flatMap(ctx -> {
                            Integer pageSize = req.getBody().hasPageSize()
                                    ? req.getBody().getPageSize()
                                    : null;
                            Integer page = req.getBody().hasPage()
                                    ? req.getBody().getPage()
                                    : null;

                            log.info("[{}][{}] listIssueComments: issueId={}, actorUserId={}, page={}, pageSize={}",
                                    ctx.requestId(), ctx.nodeId(),
                                    ctx.issueId(), ctx.actorUserId(), page, pageSize);

                            return commentService.listComments(
                                            ctx.requestId(), ctx.nodeId(),
                                            ctx.issueId(), ctx.actorUserId(), page, pageSize
                                    )
                                    .map(commentMapper::toListCommentsResponse)
                                    .doOnSuccess(response -> log.info(
                                            "[{}][{}] listIssueComments: successfully found {} comments",
                                            ctx.requestId(), ctx.nodeId(), response.getTotalCount()
                                    ))
                                    .doOnError(GrpcIssueLogging.logOnError(
                                            ctx.requestId(), ctx.nodeId(), "listIssueComments"
                                    ));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }

    private Mono<AddIssueCommentContext> validateAddComment(
            String requestId,
            String nodeId,
            String issueId,
            String authorUserId,
            String body,
            String operation
    ) {
        return Mono.zip(
                        GrpcRequestValidators.requireHeaderRequestId(requestId),
                        GrpcRequestValidators.requireHeaderNodeId(nodeId),
                        GrpcRequestValidators.parseBodyIssueId(issueId),
                        GrpcRequestValidators.parseBodyAuthorUserId(authorUserId),
                        GrpcRequestValidators.requireBodyBody(body)
                )
                .map(t -> new AddIssueCommentContext(t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
    }

    private Mono<UpdateIssueCommentContext> validateUpdateComment(
            String requestId,
            String nodeId,
            String issueId,
            String commentId,
            String actorUserId,
            String body,
            String operation
    ) {
        return Mono.zip(
                        GrpcRequestValidators.requireHeaderRequestId(requestId),
                        GrpcRequestValidators.requireHeaderNodeId(nodeId),
                        GrpcRequestValidators.parseBodyIssueId(issueId),
                        GrpcRequestValidators.parseBodyCommentId(commentId),
                        GrpcRequestValidators.parseBodyActorUserId(actorUserId),
                        GrpcRequestValidators.requireBodyBody(body)
                )
                .map(t -> new UpdateIssueCommentContext(
                        t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6()
                ))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
    }

    private Mono<DeleteIssueCommentContext> validateDeleteComment(
            String requestId,
            String nodeId,
            String issueId,
            String commentId,
            String actorUserId,
            String operation
    ) {
        return Mono.zip(
                        GrpcRequestValidators.requireHeaderRequestId(requestId),
                        GrpcRequestValidators.requireHeaderNodeId(nodeId),
                        GrpcRequestValidators.parseBodyIssueId(issueId),
                        GrpcRequestValidators.parseBodyCommentId(commentId),
                        GrpcRequestValidators.parseBodyActorUserId(actorUserId)
                )
                .map(t -> new DeleteIssueCommentContext(t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
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
                        GrpcRequestValidators.parseBodyIssueId(issueId),
                        GrpcRequestValidators.parseBodyActorUserId(actorUserId)
                )
                .map(t -> new IssueActorContext(t.getT1(), t.getT2(), t.getT3(), t.getT4()))
                .doOnError(StatusRuntimeException.class,
                        GrpcIssueLogging.logValidationError(requestId, nodeId, operation));
    }
}
