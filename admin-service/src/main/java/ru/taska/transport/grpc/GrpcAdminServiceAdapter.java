package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.*;

/**
 * gRPC-адаптер, который публикует {@link GrpcAdminReadonlyService} как protobuf endpoint.
 */
@GrpcService
@RequiredArgsConstructor
public class GrpcAdminServiceAdapter extends ReactorAdminServiceGrpc.AdminServiceImplBase {

    private final GrpcAdminReadonlyService grpcAdminReadonlyService;
    private final GrpcAdminUserManagementService grpcAdminUserManagementService;

    @Override
    public Mono<BlockUserResponse> blockUser(Mono<BlockUserRequest> request) {
        return grpcAdminUserManagementService.blockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("blockUser"));
    }

    @Override
    public Mono<UnblockUserResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return grpcAdminUserManagementService.unblockUser(request)
                .transform(GrpcExceptionHandler.withErrorHandling("unblockUser"));
    }

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
}
