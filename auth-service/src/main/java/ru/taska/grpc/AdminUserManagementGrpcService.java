package ru.taska.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.BlockUserResponse;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserResponse;
import ru.taska.mapper.AdminUserMapper;
import ru.taska.service.AdminUserManagementService;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserManagementGrpcService {

    private final AdminUserManagementService adminUserManagementService;
    private final AdminUserMapper adminUserMapper;

    @TrackMetrics(counter = "auth-service_blockUser_grpc_counter",
            timer = "auth-service_blockUser_grpc_timer")
    public Mono<BlockUserResponse> blockUser(Mono<BlockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.target_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                ))
                .flatMap(t -> {
                    UUID requestId = UUID.fromString(t.getT1());
                    String nodeId = t.getT2();
                    UUID actorUserId = t.getT3();
                    UUID targetUserId = t.getT4();
                    String reason = t.getT5();

                    log.info("[{}][{}] BlockUser request: actorUserId={}, targetUserId={}, reason={}",
                            requestId, nodeId, actorUserId, targetUserId, reason);

                    return adminUserManagementService.blockUser(targetUserId, reason, actorUserId, requestId)
                            .doOnSuccess(res ->
                                    log.info("[{}][{}] BlockUser success: targetUserId={}, oldStatus={}, newStatus={}",
                                            requestId, nodeId, targetUserId, res.oldStatus(), res.newStatus()))
                            .doOnError(error ->
                                    log.warn("[{}][{}] BlockUser failed for targetUserId={}: {}",
                                            requestId, nodeId, targetUserId, error.getMessage()));
                })
                .map(adminUserMapper::toBlockUserResponse);
    }

    @TrackMetrics(counter = "auth-service_unblockUser_grpc_counter",
            timer = "auth-service_unblockUser_grpc_timer")
    public Mono<UnblockUserResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.target_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                ))
                .flatMap(t -> {
                    UUID requestId = UUID.fromString(t.getT1());
                    String nodeId = t.getT2();
                    UUID actorUserId = t.getT3();
                    UUID targetUserId = t.getT4();
                    String reason = t.getT5();

                    log.info("[{}][{}] UnblockUser request: actorUserId={}, targetUserId={}, reason={}",
                            requestId, nodeId, actorUserId, targetUserId, reason);

                    return adminUserManagementService.unblockUser(targetUserId, reason, actorUserId, requestId)
                            .doOnSuccess(res ->
                                    log.info("[{}][{}] UnblockUser success: targetUserId={}, oldStatus={}, newStatus={}",
                                            requestId, nodeId, targetUserId, res.oldStatus(), res.newStatus()))
                            .doOnError(error ->
                                    log.warn("[{}][{}] UnblockUser failed for targetUserId={}: {}",
                                            requestId, nodeId, targetUserId, error.getMessage()));
                })
                .map(adminUserMapper::toUnblockUserResponse);
    }
}
