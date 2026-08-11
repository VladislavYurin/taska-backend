package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.BlockUserRequest;
import ru.taska.api.admin.v1.BlockUserResponse;
import ru.taska.api.admin.v1.UnblockUserRequest;
import ru.taska.api.admin.v1.UnblockUserResponse;
import ru.taska.mapper.AdminUserManagementGatewayResponseMapper;
import ru.taska.service.AdminUserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminUserManagementService {

    private final AdminUserService adminUserService;
    private final AdminUserManagementGatewayResponseMapper mapper;
    private final ObjectMapper objectMapper;

    public Mono<BlockUserResponse> blockUser(Mono<BlockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorLogin(), "body.actorLogin")
                ).flatMap(t -> {
                    UUID targetUserId = UUID.fromString(t.getT1());
                    UUID actorUserId = UUID.fromString(t.getT2());
                    String reason = t.getT3();
                    String requestId = t.getT4();
                    String nodeId = t.getT5();
                    String actorLogin = t.getT6();
//                    JsonNode actorRoles = toJsonNode(req.getBody().getActorRoles());
                    JsonNode actorRoles = objectMapper.createObjectNode(); // TODO: временно, вернуть нормальный конвертер protobuf Value -> JsonNode

                    log.info("[{}][{}] blockUser with id {}", requestId, nodeId, targetUserId);

                    return adminUserService.blockUser(
                            targetUserId,
                            actorUserId,
                            actorLogin,
                            actorRoles,
                            reason,
                            requestId,
                            nodeId
                    ).map(mapper::toGatewayBlockUserResponse);
                }));
    }

    public Mono<UnblockUserResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorLogin(), "body.actorLogin")
                ).flatMap(t -> {
                    UUID targetUserId = UUID.fromString(t.getT1());
                    UUID actorUserId = UUID.fromString(t.getT2());
                    String reason = t.getT3();
                    String requestId = t.getT4();
                    String nodeId = t.getT5();
                    String actorLogin = t.getT6();
//                    JsonNode actorRoles = toJsonNode(req.getBody().getActorRoles());
                    JsonNode actorRoles = objectMapper.createObjectNode(); // TODO: временно, вернуть нормальный конвертер protobuf Value -> JsonNode

                    log.info("[{}][{}] unblockUser with id {}", requestId, nodeId, targetUserId);

                    return adminUserService.unblockUser(
                            targetUserId,
                            actorUserId,
                            actorLogin,
                            actorRoles,
                            reason,
                            requestId,
                            nodeId
                    ).map(mapper::toGatewayUnblockUserResponse);
                }));
    }

}
