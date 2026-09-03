package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.BlockUserRequest;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequest;
import ru.taska.api.admin.v1.UnblockUserRequest;
import ru.taska.api.admin.v1.UserStatusResponse;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.mapper.AdminUserManagementMapper;
import ru.taska.service.AdminUserService;
import validator.GrpcRequestValidators;

import static ru.taska.transport.grpc.logging.GrpcAdminLogging.logOnError;
import static ru.taska.transport.grpc.logging.GrpcAdminLogging.logValidationError;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminUserManagementService {

    private final AdminUserService adminUserService;
    private final AdminUserManagementMapper managementMapper;

    public Mono<UserStatusResponse> blockUser(Mono<BlockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorLogin(), "body.actorLogin"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "blockUser"
                        ))
                        .flatMap(tuple -> {
                                String requestId = tuple.getT1();
                                String nodeId = tuple.getT2();

                                log.info("[{}][{}] blockUser", requestId, nodeId);

                                UserStatusRequestDto requestDto = managementMapper.toRequestDto(req);
                                return adminUserService.blockUser(requestId,nodeId,requestDto)
                                        .map(managementMapper::toProtoResponse)
                                        .doOnSuccess(result ->
                                                log.info("[{}][{}] blockUser: successfully block user",
                                                        requestId, nodeId)
                                        )
                                        .doOnError(logOnError(requestId, nodeId, "blockUser"));
                        })
                );
    }

    public Mono<UserStatusResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorLogin(), "body.actorLogin"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "unblockUser"
                        ))
                        .flatMap(tuple -> {
                                String requestId = tuple.getT1();
                                String nodeId = tuple.getT2();

                                log.info("[{}][{}] unblockUser", requestId, nodeId);

                                UserStatusRequestDto requestDto = managementMapper.toRequestDto(req);
                                return adminUserService.unblockUser(requestId,nodeId,requestDto)
                                        .map(managementMapper::toProtoResponse)
                                        .doOnSuccess(result ->
                                                log.info("[{}][{}] unblockUser: successfully unblock user",
                                                        requestId, nodeId)
                                        )
                                        .doOnError(logOnError(requestId, nodeId, "unblockUser"));
                        })
                );
    }

    public Mono<UserStatusResponse> resetCredentialLockout(Mono<ResetCredentialLockoutRequest> request){
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorLogin(), "body.actorLogin"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "resetCredentialLockout"
                        ))
                        .flatMap(tuple->{
                                String requestId = tuple.getT1();
                                String nodeId = tuple.getT2();

                                log.info("[{}][{}] resetCredentialLockout", requestId, nodeId);

                                UserStatusRequestDto requestDto = managementMapper.toRequestDto(req);
                                return adminUserService.resetCredentialLockout(requestId,nodeId,requestDto)
                                        .map(managementMapper::toProtoResponse)
                                        .doOnSuccess(result ->
                                                log.info("[{}][{}] resetCredentialLockout: successfully reset credentials",
                                                        requestId, nodeId)
                                        )
                                        .doOnError(logOnError(requestId, nodeId, "resetCredentialLockout"));
                        })
                );
    }
}
