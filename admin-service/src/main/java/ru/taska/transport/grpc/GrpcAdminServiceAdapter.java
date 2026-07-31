package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;

/**
 * gRPC-адаптер, который публикует {@link GrpcAdminService} как protobuf endpoint.
 */
@GrpcService
@RequiredArgsConstructor
public class GrpcAdminServiceAdapter extends ReactorAdminServiceGrpc.AdminServiceImplBase {

    private final GrpcAdminService grpcAdminService;

    @Override
    public Mono<GetCatalogResponse> getCatalog(Mono<GetCatalogRequest> request) {
        return grpcAdminService.getCatalog(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getCatalog"));
    }
}
