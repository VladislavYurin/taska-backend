package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.service.MetadataService;
import validator.GrpcRequestValidators;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminService {

    private final MetadataService metadataService;
    private final MetadataCatalogMapper mapper;

    /**
     * Обрабатывает gRPC-запрос на получение каталога метаданных.
     */
    public Mono<GetCatalogResponse> getCatalog(Mono<GetCatalogRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    log.info("[{}][{}] getCatalog", requestId, nodeId);

                    return metadataService.getCatalog()
                            .map(mapper::toGetCatalogResponse);
                }));
    }
}
