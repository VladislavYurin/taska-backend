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
    private final ru.taska.service.UserDisplayNameAdminService displayNames;

    public Mono<ru.taska.api.admin.v1.UpdateUserDisplayNameResponse> updateUserDisplayName(
            Mono<ru.taska.api.admin.v1.UpdateUserDisplayNameRequest> request) {
        return request.flatMap(req -> {
            if (req.getActorRole() != ru.taska.api.common.v1.GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN) {
                return Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.PERMISSION_DENIED, "Global admin role is required"));
            }
            return Mono.zip(
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(req.getTargetUserId(), "targetUserId"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getActorDisplayName(), "actorDisplayName"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getActorEmail(), "actorEmail"))
                    .flatMap(ids -> displayNames.update(ids.getT1(), ids.getT2(),
                            UserStatusRequestDto.builder().targetUserId(ids.getT3()).actorUserId(ids.getT4())
                                    .actorDisplayName(req.getActorDisplayName()).actorEmail(req.getActorEmail())
                                    .role(ru.taska.domain.GlobalRole.GLOBAL_ADMIN).reason("Display name updated by administrator").build(),
                            ru.taska.util.DisplayNames.normalize(req.getDisplayName())))
                    .map(change -> ru.taska.api.admin.v1.UpdateUserDisplayNameResponse.newBuilder()
                            .setUserId(change.userId().toString()).setDisplayName(change.displayName()).build());
        });
    }

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
                                req.getBody().getActorDisplayName(), "body.actorDisplayName"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorEmail(), "body.actorEmail"),
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
                                req.getBody().getActorDisplayName(), "body.actorDisplayName"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorEmail(), "body.actorEmail"),
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
                                req.getBody().getActorDisplayName(), "body.actorDisplayName"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorEmail(), "body.actorEmail"),
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
