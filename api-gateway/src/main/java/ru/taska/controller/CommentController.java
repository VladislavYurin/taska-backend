package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.dto.CommentRequestDto;
import ru.taska.domain.dto.CommentResponseDto;
import ru.taska.domain.dto.CommentsListResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcCommentServiceClient;
import jakarta.validation.Valid;

import java.util.UUID;

import static ru.taska.domain.EndpointSecurity.PROTECTED;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/issues/{issueId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final GatewayRequestExecutor executor;
    private final GrpcCommentServiceClient commentClient;  // ← Используем клиент

    @GetMapping
    public Mono<CommentsListResponseDto> listComments(
            @PathVariable UUID projectId,
            @PathVariable UUID issueId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context -> {
            String requestId = context.requestId();
            String nodeId = context.nodeId();
            String userId = context.userContext().userId();

            log.info("[{}] List comments: projectId={}, issueId={}, userId={}, page={}, pageSize={}",
                    requestId, projectId, issueId, userId, page, pageSize);

            return commentClient.listComments(issueId, UUID.fromString(userId), page, pageSize, context)
                    .doOnSuccess(response ->
                            log.info("[{}] Successfully retrieved {} comments for issue {}",
                                    requestId, response.getTotalCount(), issueId)
                    );
        });
    }

    @PostMapping
    public Mono<CommentResponseDto> addComment(
            @PathVariable UUID projectId,
            @PathVariable UUID issueId,
            @Valid @RequestBody CommentRequestDto request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context -> {
            String requestId = context.requestId();
            String nodeId = context.nodeId();
            String userId = context.userContext().userId();

            log.info("[{}] Add comment: projectId={}, issueId={}, userId={}",
                    requestId, projectId, issueId, userId);

            return commentClient.addComment(issueId, UUID.fromString(userId), request.getBody(), context)
                    .doOnSuccess(response ->
                            log.info("[{}] Successfully added comment with id {} to issue {}",
                                    requestId, response.getId(), issueId)
                    );
        });
    }

    @PutMapping("/{commentId}")
    public Mono<CommentResponseDto> updateComment(
            @PathVariable UUID projectId,
            @PathVariable UUID issueId,
            @PathVariable UUID commentId,
            @Valid @RequestBody CommentRequestDto request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context -> {
            String requestId = context.requestId();
            String nodeId = context.nodeId();
            String userId = context.userContext().userId();

            log.info("[{}] Update comment: projectId={}, issueId={}, commentId={}, userId={}",
                    requestId, projectId, issueId, commentId, userId);

            return commentClient.updateComment(issueId, commentId, UUID.fromString(userId), request.getBody(), context)
                    .doOnSuccess(response ->
                            log.info("[{}] Successfully updated comment with id {}",
                                    requestId, commentId)
                    );
        });
    }

    @DeleteMapping("/{commentId}")
    public Mono<ResponseEntity<Void>> deleteComment(
            @PathVariable UUID projectId,
            @PathVariable UUID issueId,
            @PathVariable UUID commentId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context -> {
            String requestId = context.requestId();
            String nodeId = context.nodeId();
            String userId = context.userContext().userId();

            log.info("[{}] Delete comment: projectId={}, issueId={}, commentId={}, userId={}",
                    requestId, projectId, issueId, commentId, userId);

            return commentClient.deleteComment(issueId, commentId, UUID.fromString(userId), context)
                    .thenReturn(ResponseEntity.noContent().<Void>build())
                    .doOnSuccess(response ->
                            log.info("[{}] Successfully deleted comment with id {}",
                                    requestId, commentId)
                    );
        });
    }
}