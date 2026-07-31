package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.CommentResponseDto;
import ru.taska.domain.dto.CommentsListResponseDto;
import ru.taska.mapper.CommentRestMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с комментариями в issue-service.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcCommentServiceClient {

    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final CommentRestMapper commentRestMapper;
    private final GrpcClientProperties properties;

    /**
     * Получает список комментариев к задаче.
     */
    public Mono<CommentsListResponseDto> listComments(
            UUID issueId,
            UUID actorUserId,
            Integer page,
            Integer pageSize,
            GatewayContext context
    ) {
        log.info("[{}] Calling listComments for issue: {}", context.requestId(), issueId);

        ListIssueCommentsRequest request = ListIssueCommentsRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(ListIssueCommentsRequestBody.newBuilder()
                        .setIssueId(issueId.toString())
                        .setActorUserId(actorUserId.toString())
                        .setPage(page != null ? page : 0)
                        .setPageSize(pageSize != null ? pageSize : 20)
                        .build())
                .build();

        return dynamicStub().listIssueComments(Mono.just(request))
                .map(commentRestMapper::toCommentsListResponseDto);
    }

    /**
     * Добавляет комментарий к задаче.
     */
    public Mono<CommentResponseDto> addComment(
            UUID issueId,
            UUID authorUserId,
            String body,
            GatewayContext context
    ) {
        log.info("[{}] Calling addComment for issue: {}", context.requestId(), issueId);

        AddIssueCommentRequest request = AddIssueCommentRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(AddIssueCommentRequestBody.newBuilder()
                        .setIssueId(issueId.toString())
                        .setAuthorUserId(authorUserId.toString())
                        .setBody(body)
                        .build())
                .build();

        return dynamicStub().addIssueComment(Mono.just(request))
                .map(commentRestMapper::toCommentResponseDto);
    }

    /**
     * Обновляет комментарий.
     */
    public Mono<CommentResponseDto> updateComment(
            UUID issueId,
            UUID commentId,
            UUID actorUserId,
            String body,
            GatewayContext context
    ) {
        log.info("[{}] Calling updateComment: {}", context.requestId(), commentId);

        UpdateIssueCommentRequest request = UpdateIssueCommentRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(UpdateIssueCommentRequestBody.newBuilder()
                        .setIssueId(issueId.toString())
                        .setCommentId(commentId.toString())
                        .setActorUserId(actorUserId.toString())
                        .setBody(body)
                        .build())
                .build();

        return dynamicStub().updateIssueComment(Mono.just(request))
                .map(commentRestMapper::toCommentResponseDto);
    }

    /**
     * Удаляет комментарий.
     */
    public Mono<Void> deleteComment(
            UUID issueId,
            UUID commentId,
            UUID actorUserId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteComment: {}", context.requestId(), commentId);

        DeleteIssueCommentRequest request = DeleteIssueCommentRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(DeleteIssueCommentRequestBody.newBuilder()
                        .setIssueId(issueId.toString())
                        .setCommentId(commentId.toString())
                        .setActorUserId(actorUserId.toString())
                        .build())
                .build();

        return dynamicStub().deleteIssueComment(Mono.just(request))
                .then();
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub.withDeadlineAfter(
                properties.issueService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}