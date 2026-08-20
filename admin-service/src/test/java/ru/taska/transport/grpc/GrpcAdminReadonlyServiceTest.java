package ru.taska.transport.grpc;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdRequestBody;
import ru.taska.api.admin.v1.GetTableRowByIdResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.common.v1.Header;
import ru.taska.dto.CatalogDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.mapper.ListTableRowsMapper;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;

import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class GrpcAdminReadonlyServiceTest {

    private static final String REQUEST_ID = "req-1";
    private static final String NODE_ID = "node-1";

    @Mock
    private MetadataService metadataService;

    @Mock
    private MetadataCatalogMapper metadataCatalogMapper;

    @Mock
    private AdminReadonlyService adminReadonlyService;

    @Mock
    private ListTableRowsMapper listTableRowsMapper;

    @InjectMocks
    private GrpcAdminReadonlyService grpcAdminReadonlyService;

    @Test
    void getCatalog_blankRequestId_fails() {
        GetCatalogRequest request = GetCatalogRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getCatalog(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void getCatalog_blankNodeId_fails() {
        GetCatalogRequest request = GetCatalogRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(" ").build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getCatalog(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void getCatalog_success_mapsResponse() {
        CatalogDto catalogDto = new CatalogDto(List.of());
        GetCatalogResponse grpcResponse = GetCatalogResponse.newBuilder().build();

        Mockito.when(metadataService.getCatalog()).thenReturn(Mono.just(catalogDto));
        Mockito.when(metadataCatalogMapper.toGetCatalogResponse(catalogDto)).thenReturn(grpcResponse);

        GetCatalogRequest request = GetCatalogRequest.newBuilder()
                .setHeader(validHeader())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getCatalog(Mono.just(request)))
                .expectNext(grpcResponse)
                .verifyComplete();
    }

    @Test
    void listTableRows_success_delegatesToAdminService() {
        ListTableRowsRequest grpcRequest = ListTableRowsRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(ListTableRowsRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .build())
                .build();

        ListTableRowsRequestDto requestDto = new ListTableRowsRequestDto(
                "auth", "users", null, null, null, null, Map.of()
        );
        ListTableRowsResponseDto responseDto = new ListTableRowsResponseDto(
                List.of(), 0L, 0, 20, List.of(), "auth", "users"
        );
        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();

        Mockito.when(listTableRowsMapper.toRequestDto(grpcRequest)).thenReturn(requestDto);
        Mockito.when(adminReadonlyService.listTableRows(requestDto, REQUEST_ID, NODE_ID)).thenReturn(Mono.just(responseDto));
        Mockito.when(listTableRowsMapper.toListTableRowsResponse(responseDto)).thenReturn(grpcResponse);

        StepVerifier.create(grpcAdminReadonlyService.listTableRows(Mono.just(grpcRequest)))
                .expectNext(grpcResponse)
                .verifyComplete();

        Mockito.verify(adminReadonlyService).listTableRows(requestDto, REQUEST_ID, NODE_ID);
    }

    @Test
    void getTableRowById_success_delegatesToAdminService() {
        GetTableRowByIdRequest grpcRequest = GetTableRowByIdRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(GetTableRowByIdRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .setId("row-1")
                        .build())
                .build();

        GetTableRowByIdRequestDto requestDto = new GetTableRowByIdRequestDto("auth", "users", "row-1");
        GetTableRowByIdResponseDto responseDto = new GetTableRowByIdResponseDto(Map.of("id", "row-1"));
        GetTableRowByIdResponse grpcResponse = GetTableRowByIdResponse.newBuilder().build();

        Mockito.when(listTableRowsMapper.toGetByIdRequestDto(grpcRequest)).thenReturn(requestDto);
        Mockito.when(adminReadonlyService.getTableRowById(requestDto, REQUEST_ID, NODE_ID)).thenReturn(Mono.just(responseDto));
        Mockito.when(listTableRowsMapper.toGetTableRowByIdResponse(responseDto)).thenReturn(grpcResponse);

        StepVerifier.create(grpcAdminReadonlyService.getTableRowById(Mono.just(grpcRequest)))
                .expectNext(grpcResponse)
                .verifyComplete();

        Mockito.verify(adminReadonlyService).getTableRowById(requestDto, REQUEST_ID, NODE_ID);
    }

    private static Header validHeader() {
        return Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }
}
