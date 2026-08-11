package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserResponse;
import ru.taska.api.auth.admin.inviteuser.v1.ReactorAdminInviteUserServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class AdminInviteUserGrpcServiceAdapter extends ReactorAdminInviteUserServiceGrpc.AdminInviteUserServiceImplBase{

    private final AdminInviteUserGrpcService adminInviteUserGrpcService;

    @Override
    public Mono<InviteUserResponse> inviteUser(Mono<InviteUserRequest> request) {
        return adminInviteUserGrpcService.inviteUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("inviteUser"));
    }
}
