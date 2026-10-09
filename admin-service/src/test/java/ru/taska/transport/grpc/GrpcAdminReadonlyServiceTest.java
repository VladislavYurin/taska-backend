package ru.taska.transport.grpc;


import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.admin.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.PageResult;

import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.dto.CatalogDto;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.mapper.ListTableRowsMapper;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.mapper.ProblematicOutboxEventMapper;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;
import ru.taska.service.ProblematicOutboxEventService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class GrpcAdminReadonlyServiceTest {

    private static final String REQUEST_ID = "req-1";
    private static final String NODE_ID = "node-1";
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();

    @Mock
    private MetadataService metadataService;

    @Mock
    private MetadataCatalogMapper metadataCatalogMapper;

    @Mock
    private AdminReadonlyService adminReadonlyService;

    @Mock
    private ListTableRowsMapper listTableRowsMapper;

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private ProblematicOutboxEventService problematicOutboxEventService;

    @Mock
    private ProblematicOutboxEventMapper problematicOutboxEventMapper;

    @InjectMocks
    private GrpcAdminReadonlyService grpcAdminReadonlyService;

    // ==================== getCatalog ====================

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
    void getCatalog_success_delegatesToMetadataService() {
        GetCatalogRequest grpcRequest = GetCatalogRequest.newBuilder()
                .setHeader(validHeader())
                .build();

        CatalogDto catalogDto = new CatalogDto(List.of());
        GetCatalogResponse grpcResponse = GetCatalogResponse.newBuilder().build();

        Mockito.when(metadataService.getCatalog()).thenReturn(Mono.just(catalogDto));
        Mockito.when(metadataCatalogMapper.toGetCatalogResponse(catalogDto)).thenReturn(grpcResponse);

        StepVerifier.create(grpcAdminReadonlyService.getCatalog(Mono.just(grpcRequest)))
                .expectNext(grpcResponse)
                .verifyComplete();

        Mockito.verify(metadataService).getCatalog();
    }

    // ==================== listTableRows ====================

    @Test
    void listTableRows_blankRequestId_fails() {
        ListTableRowsRequest request = ListTableRowsRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                .setBody(ListTableRowsRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.listTableRows(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void listTableRows_blankNodeId_fails() {
        ListTableRowsRequest request = ListTableRowsRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(" ").build())
                .setBody(ListTableRowsRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.listTableRows(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
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

    // ==================== getTableRowById ====================

    @Test
    void getTableRowById_blankRequestId_fails() {
        GetTableRowByIdRequest request = GetTableRowByIdRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                .setBody(GetTableRowByIdRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .setId("row-1")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getTableRowById(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void getTableRowById_blankNodeId_fails() {
        GetTableRowByIdRequest request = GetTableRowByIdRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(" ").build())
                .setBody(GetTableRowByIdRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .setTableName("users")
                        .setId("row-1")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getTableRowById(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
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

    // ==================== getProblematicOutboxEventsSummary ====================

    @Test
    void getProblematicOutboxEventsSummary_blankRequestId_fails() {
        GetProblematicOutboxEventsSummaryRequest request = GetProblematicOutboxEventsSummaryRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                .setBody(GetProblematicOutboxEventsSummaryRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getProblematicOutboxEventsSummary(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void getProblematicOutboxEventsSummary_blankNodeId_fails() {
        GetProblematicOutboxEventsSummaryRequest request = GetProblematicOutboxEventsSummaryRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(" ").build())
                .setBody(GetProblematicOutboxEventsSummaryRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .build())
                .build();

        StepVerifier.create(grpcAdminReadonlyService.getProblematicOutboxEventsSummary(Mono.just(request)))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void getProblematicOutboxEventsSummary_success_delegatesToService() {
        GetProblematicOutboxEventsSummaryRequest grpcRequest = GetProblematicOutboxEventsSummaryRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(GetProblematicOutboxEventsSummaryRequestBody.newBuilder()
                        .setServiceKey("auth")
                        .build())
                .build();

        GetProblematicOutboxEventsSummaryResponseDto responseDto = new GetProblematicOutboxEventsSummaryResponseDto(
                List.of(), List.of(), false
        );
        GetProblematicOutboxEventsSummaryResponse grpcResponse = GetProblematicOutboxEventsSummaryResponse.newBuilder().build();

        Mockito.when(problematicOutboxEventService.getProblematicOutboxEventsSummary("auth", REQUEST_ID, NODE_ID)).thenReturn(Mono.just(responseDto));
        Mockito.when(problematicOutboxEventMapper.toProto(responseDto)).thenReturn(grpcResponse);

        StepVerifier.create(grpcAdminReadonlyService.getProblematicOutboxEventsSummary(Mono.just(grpcRequest)))
                .expectNext(grpcResponse)
                .verifyComplete();

        Mockito.verify(problematicOutboxEventService).getProblematicOutboxEventsSummary("auth", REQUEST_ID, NODE_ID);
    }

    // ==================== helpers ====================

    private static Header validHeader() {
        return Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }

    @Test
    void listAuditEntries_Success() {
        ListAuditEntriesRequest request = getListAuditEntriesRequest();

        FilterAuditDTO filterDTO = new FilterAuditDTO(
                ACTOR_USER_ID,
                "action",
                "targetService",
                "targetTable",
                "targetId",
                "requestId",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-31T23:59:59.999999999Z")
        );

        List<AuditEntriesResponseDto> expectedEntries = List.of(
                new AuditEntriesResponseDto(
                        ACTOR_USER_ID,
                        "actorLogin",
                        "action",
                        "targetService",
                        "targetTable",
                        "targetId",
                        null,
                        null,
                        "reason",
                        "requestId",
                        Instant.parse("2024-01-01T00:00:00Z")
                )
        );

        PageResult<AuditEntriesResponseDto> expectedPageResult =
                new PageResult<>(expectedEntries, 1L, 1, 10);

        ListAuditEntriesResponse expectedResponse = ListAuditEntriesResponse.newBuilder()
                .addEntries(ListAuditEntry.newBuilder()
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setActorLogin("actorLogin")
                        .setAction("action")
                        .setTargetService("targetService")
                        .setTargetTable("targetTable")
                        .setTargetId("targetId")
                        .setReason("reason")
                        .setRequestId("requestId")
                        .setCreatedAt(Timestamp.newBuilder()
                                .setSeconds(Instant.parse("2024-01-01T00:00:00Z").getEpochSecond())
                                .setNanos(Instant.parse("2024-01-01T00:00:00Z").getNano())
                                .build())
                        .build())
                .setCurrentPage(1)
                .setPageSize(10)
                .setTotalRows(1)
                .setTotalPages(1)
                .setHasNext(false)
                .setHasPrev(false)
                .build();

        Mockito.when(auditLogMapper.toFilterDTO(
                eq(ACTOR_USER_ID), eq("action"), eq("targetService"),
                eq("targetTable"), eq("targetId"), eq("requestId"),
                eq(Instant.parse("2024-01-01T00:00:00Z")),
                eq(Instant.parse("2024-01-31T23:59:59.999999999Z"))
        )).thenReturn(filterDTO);

        Mockito.when(adminReadonlyService.listAuditEntries(
                eq(filterDTO), eq(1), eq(10)
        )).thenReturn(Mono.just(expectedPageResult));

        Mockito.when(auditLogMapper.toAuditProto(any(PageResult.class)))
                .thenReturn(expectedResponse);

        StepVerifier.create(grpcAdminReadonlyService.listAuditEntries(Mono.just(request)))
                .expectNext(expectedResponse)
                .verifyComplete();
    }

    @Test
    void listAuditEntries_OnlyDateRange() {
        ListAuditEntriesRequest request = ListAuditEntriesRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(NODE_ID).build())
                .setBody(ListAuditEntriesRequestBody.newBuilder()
                        .setCreatedAtFrom("2024-01-01")
                        .setCreatedAtTo("2024-01-31")
                        .build())
                .build();

        PageResult<AuditEntriesResponseDto> pageResult = new PageResult<>(
                List.of(), 0L, 0, 10
        );

        Mockito.when(auditLogMapper.toFilterDTO(
                        any(), any(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(FilterAuditDTO.builder().build());

        Mockito.when(adminReadonlyService.listAuditEntries(
                        any(FilterAuditDTO.class),
                        nullable(Integer.class),
                        nullable(Integer.class)))
                .thenReturn(Mono.just(pageResult));

        Mockito.when(auditLogMapper.toAuditProto(any()))
                .thenReturn(ListAuditEntriesResponse.newBuilder().build());

        StepVerifier.create(grpcAdminReadonlyService.listAuditEntries(Mono.just(request)))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void listAuditEntries_success_delegatesToAdminService() {
        ListAuditEntriesRequest request = getListAuditEntriesRequest();

        FilterAuditDTO filterDTO = new FilterAuditDTO(ACTOR_USER_ID, "action", "targetService", "targetTable", "00000000-0000-0000-0000-000000000001", "requestId", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-31T23:59:59.999999999Z")
        );

        List<AuditEntriesResponseDto> auditLogs = List.of(
                AuditEntriesResponseDto.builder()
                        .actorUserId(ACTOR_USER_ID)
                        .action("action")
                        .build()
        );

        PageResult<AuditEntriesResponseDto> responseDto = new PageResult<>
                (auditLogs, 1, 10, 1);

        ListAuditEntriesResponse grpcResponse = ListAuditEntriesResponse.newBuilder()
                .addEntries(ListAuditEntry.newBuilder()
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setAction("action")
                        .build())
                .setCurrentPage(1)
                .setPageSize(10)
                .setTotalRows(1)
                .setTotalPages(1)
                .setHasNext(false)
                .setHasPrev(false)
                .build();

        Mockito.when(auditLogMapper.toFilterDTO(
                        Mockito.eq(ACTOR_USER_ID),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(Instant.class),
                        Mockito.any(Instant.class)))
                .thenReturn(filterDTO);

        Mockito.when(adminReadonlyService.listAuditEntries(filterDTO, 1, 10))
                .thenReturn(Mono.just(responseDto));

        Mockito.when(auditLogMapper.toAuditProto(responseDto))
                .thenReturn(grpcResponse);

        StepVerifier.create(grpcAdminReadonlyService.listAuditEntries(Mono.just(request)))
                .expectNext(grpcResponse)
                .verifyComplete();

        Mockito.verify(adminReadonlyService).listAuditEntries(filterDTO, 1, 10);
    }

    private static @NonNull ListAuditEntriesRequest getListAuditEntriesRequest() {
        ListAuditEntriesRequest request = ListAuditEntriesRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(NODE_ID).build())
                .setBody(ListAuditEntriesRequestBody.newBuilder()
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setAction("action")
                        .setTargetService("targetService")
                        .setTargetTable("targetTable")
                        .setTargetId("targetId")
                        .setRequestId("requestId")
                        .setCreatedAtFrom("2024-01-01")
                        .setCreatedAtTo("2024-01-31")
                        .setPageSize(10)
                        .setPage(1)
                        .build())
                .build();
        return request;
    }
}
