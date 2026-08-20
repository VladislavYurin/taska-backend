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
import ru.taska.exception.DomainException;
import ru.taska.mapper.CommentMapper;
import ru.taska.service.CommentService;
import ru.taska.transport.grpc.logging.GrpcIssueLogging;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueCommentService {

    private final CommentService commentService;
    private final CommentMapper commentMapper;

    @TrackMetrics(counter = "issue-service_add-issue-comment_grpc_counter",
            timer = "issue-service_add-issue-comment_grpc_timer")
    public Mono<AddIssueCommentResponse> addIssueComment(Mono<AddIssueCommentRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getAuthorUserId(), "body.authorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getBody(), "body.body"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "addIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID authorUserId = t.getT4();
                            String body = t.getT5();

                            log.info("[{}][{}] addIssueComment: issueId={}, authorUserId={}",
                                    requestId, nodeId, issueId, authorUserId);

                            return commentService.addComment(requestId, nodeId,issueId, authorUserId, body)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] addIssueComment: successfully added, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "addIssueComment")
                                    );
                        }))
                .map(commentMapper::toAddCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_update-issue-comment_grpc_counter",
            timer = "issue-service_update-issue-comment_grpc_timer")
    public Mono<UpdateIssueCommentResponse> updateIssueComment(Mono<UpdateIssueCommentRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getCommentId(), "body.commentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getBody(), "body.body"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "updateIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID commentId = t.getT4();
                            UUID actorUserId = t.getT5();
                            String body = t.getT6();



                            log.info("[{}][{}] updateIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    requestId, nodeId, issueId, commentId, actorUserId);

                            return commentService.updateComment(requestId, nodeId, issueId, commentId, actorUserId, body)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] updateIssueComment: successfully updated, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "updateIssueComment")
                                    );
                        }))
                .map(commentMapper::toUpdateCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_delete-issue-comment_grpc_counter",
            timer = "issue-service_delete-issue-comment_grpc_timer")
    public Mono<DeleteIssueCommentResponse> deleteIssueComment(Mono<DeleteIssueCommentRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getCommentId(), "body.commentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "deleteIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID commentId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] deleteIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    requestId, nodeId, issueId, commentId, actorUserId);

                            return commentService.deleteComment(requestId, nodeId, issueId, commentId, actorUserId)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] deleteIssueComment: successfully deleted, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "deleteIssueComment")
                                    );
                        }))
                .map(commentMapper::toDeleteCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_list-issue-comments_grpc_counter",
            timer = "issue-service_list-issue-comments_grpc_timer")
    public Mono<ListIssueCommentsResponse> listIssueComments(Mono<ListIssueCommentsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listIssueComments")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            Integer pageSize = req.getBody().hasPageSize()
                                    ? req.getBody().getPageSize()
                                    : null;
                            Integer page = req.getBody().hasPage()
                                    ? req.getBody().getPage()
                                    : null;

                            log.info("[{}][{}] listIssueComments: issueId={}, actorUserId={}, page={}, pageSize={}",
                                    requestId, nodeId, issueId, actorUserId, page, pageSize);

                            return commentService.listComments(requestId, nodeId, issueId, actorUserId, page, pageSize)
                                    .map(result -> ListIssueCommentsResponse.newBuilder()
                                            .addAllComments(
                                                    result.items().stream()
                                                            .map(commentMapper::toCommentProto)
                                                            .toList()
                                            )
                                            .setTotalCount((int) result.totalCount())
                                            .build()
                                    )
                                    .doOnSuccess(result ->
                                            {
                                                assert result != null;
                                                log.info("[{}][{}] listIssueComments: successfully found {} comments",
                                                        requestId, nodeId, result.getTotalCount());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "listIssueComments")
                                    );
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }
}
