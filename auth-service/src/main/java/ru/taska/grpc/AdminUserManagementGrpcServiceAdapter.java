package ru.taska.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.management.v1.*;

@Service
@RequiredArgsConstructor
public class AdminUserManagementGrpcServiceAdapter extends ReactorAdminUserManagementServiceGrpc.AdminUserManagementServiceImplBase {

    private final AdminUserManagementGrpcService adminUserManagementGrpcService;

    @Override
    public Mono<BlockUserResponse> blockUser(Mono<BlockUserRequest> request) {
        return adminUserManagementGrpcService.blockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("blockUser"));
    }

    @Override
    public Mono<UnblockUserResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return adminUserManagementGrpcService.unblockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("unblockUser"));
    }
}
