package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.ReactorAdminUserManagementServiceGrpc;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.UserStatusAuthResponse;
import ru.taska.api.auth.admin.management.v1.UserCredentialStateAuthResponse;

@GrpcService
@RequiredArgsConstructor
public class AdminUserManagementGrpcServiceAdapter extends ReactorAdminUserManagementServiceGrpc.AdminUserManagementServiceImplBase {

    private final AdminUserManagementGrpcService adminUserManagementGrpcService;

    @Override
    public Mono<UserStatusAuthResponse> blockUser(Mono<BlockUserRequest> request) {
        return adminUserManagementGrpcService.blockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("blockUser"));
    }

    @Override
    public Mono<UserStatusAuthResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return adminUserManagementGrpcService.unblockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("unblockUser"));
    }
    @Override
    public Mono<UserCredentialStateAuthResponse> resetCredentialLockout(Mono<ResetCredentialLockoutAuthRequest> request) {
        return adminUserManagementGrpcService.resetCredentialLockout(request)
                .transform(GrpcExceptionHandler.withErrorHandling("resetCredentialLockout"));
    }
}
