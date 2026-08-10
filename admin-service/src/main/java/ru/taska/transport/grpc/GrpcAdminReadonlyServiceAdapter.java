package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;

/**
 * gRPC-адаптер, который публикует {@link GrpcAdminReadonlyService} как protobuf endpoint.
 */
@GrpcService
@RequiredArgsConstructor
public class GrpcAdminReadonlyServiceAdapter extends ReactorAdminServiceGrpc.AdminServiceImplBase {

    private final GrpcAdminReadonlyService grpcAdminReadonlyService;

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
