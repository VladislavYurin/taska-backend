package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.BlockUserRequest;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryResponse;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequest;
import ru.taska.api.admin.v1.UnblockUserRequest;
import ru.taska.api.admin.v1.UserStatusResponse;

/**
 * gRPC-адаптер, который публикует {@link GrpcAdminReadonlyService} как protobuf endpoint.
 */
@GrpcService
@RequiredArgsConstructor
public class GrpcAdminServiceAdapter extends ReactorAdminServiceGrpc.AdminServiceImplBase {

    private final GrpcAdminReadonlyService grpcAdminReadonlyService;
    private final GrpcAdminUserManagementService grpcAdminUserManagementService;

    @Override
    public Mono<GetCatalogResponse> getCatalog(Mono<GetCatalogRequest> request) {
        return grpcAdminReadonlyService.getCatalog(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getCatalog"));
    }

    @Override
    public Mono<ListTableRowsResponse> listTableRows(Mono<ListTableRowsRequest> request) {
        return grpcAdminReadonlyService.listTableRows(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listTableRows"));
    }

    @Override
    public Mono<GetTableRowByIdResponse> getTableRowById(Mono<GetTableRowByIdRequest> request) {
        return grpcAdminReadonlyService.getTableRowById(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getTableRowById"));
    }

    @Override
    public Mono<GetProblematicOutboxEventsSummaryResponse> getProblematicOutboxEventsSummary(Mono<GetProblematicOutboxEventsSummaryRequest> request) {
        return grpcAdminReadonlyService.getProblematicOutboxEventsSummary(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProblematicOutboxEventsSummary"));
    }

    @Override
    public Mono<UserStatusResponse> blockUser(Mono<BlockUserRequest> request) {
        return grpcAdminUserManagementService.blockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("blockUser"));
    }

    @Override
    public Mono<UserStatusResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return grpcAdminUserManagementService.unblockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("unblockUser"));
    }

    @Override
    public Mono<UserStatusResponse> resetCredentialLockout(Mono<ResetCredentialLockoutRequest> request) {
        return grpcAdminUserManagementService.resetCredentialLockout(request)
                .transform(GrpcExceptionHandler.withErrorHandling("resetCredentialLockout"));
    }
}
