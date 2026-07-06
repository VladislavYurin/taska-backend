package ru.taska.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserResponse;
import ru.taska.api.auth.admin.inviteuser.v1.ReactorAdminInviteUserServiceGrpc;
import ru.taska.service.AdminInviteUserService;
import validator.GrpcRequestValidators;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class AdminInviteUserGrpcService extends ReactorAdminInviteUserServiceGrpc.AdminInviteUserServiceImplBase {

    private final AdminInviteUserService adminInviteUserService;

    @Override
    public Mono<InviteUserResponse> inviteUser(Mono<InviteUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireValidEmail(req.getBody().getEmail(), "body.email"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getDisplayName(), "body.displayName")
                ).thenReturn(req))
                .flatMap(req -> {
                    String requestId = req.getHeader().getRequestId();
                    String nodeId = req.getHeader().getNodeId();
                    String email = req.getBody().getEmail();
                    String displayName = req.getBody().getDisplayName();

                    log.info("[{}][{}] AdminInviteUser: email={}, displayName={}",
                             requestId, nodeId, email, displayName);
                    return adminInviteUserService.inviteUser(req)
                                                 .doOnNext(resp -> log.info("[{}][{}] user invited: userId={}",
                                                                            requestId, nodeId, resp.getUserId()));
                })
                .transform(GrpcExceptionHandler.withErrorHandling("inviteUser"));
    }
}
