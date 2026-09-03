package ru.taska.transport.grpc;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.admin.v1.Catalog;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ProblematicOutboxEventsSummaryResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.mapper.AdminDataMapper;
import ru.taska.mapper.AdminUserManagementMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcAdminServiceClient Tests")
class GrpcAdminServiceClientTest {

    // ==================== ГЛОБАЛЬНЫЕ КОНСТАНТЫ ====================

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";
    private static final String REQUEST_ID = "test-req-id";
    private static final String NODE_ID = "test-node-id";

    @Mock
    private ReactorAdminServiceGrpc.ReactorAdminServiceStub adminServiceStub;

    @Mock
    private AdminDataMapper mapper;

    @Mock
    private AdminUserManagementMapper adminUserManagementMapper;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service adminServiceProps;

    private GrpcAdminServiceClient client;

    // ==================== SETUP ====================

    @BeforeEach
    void setUp() {
        // Настраиваем properties
        Mockito.when(properties.adminService()).thenReturn(adminServiceProps);
        Mockito.when(adminServiceProps.deadlineDuration()).thenReturn(Duration.ofMillis(3000));

        Mockito.when(adminServiceStub.withDeadlineAfter(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any()
        )).thenReturn(adminServiceStub);

        client = new GrpcAdminServiceClient(adminServiceStub, mapper, adminUserManagementMapper, properties);
    }

    // ==================== HELPER ====================

    private GatewayContext createContext() {
        return new GatewayContext(REQUEST_ID, NODE_ID, null);
    }

    // ==================== ТЕСТЫ getCatalog ====================

    @Test
    @DisplayName("Должен вызвать gRPC getCatalog и вернуть отмапленный ответ")
    void shouldGetCatalog() {
        // given
        GatewayContext context = createContext();

        GetCatalogResponse grpcResponse = GetCatalogResponse.newBuilder()
                .setCatalog(Catalog.newBuilder().build())
                .build();

        MetadataResponse restResponse = new MetadataResponse();

        Mockito.when(adminServiceStub.getCatalog(Mockito.any(GetCatalogRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestGetCatalogResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<MetadataResponse> result = client.getCatalog(context);

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        // Проверяем, что запрос сформирован правильно
        ArgumentCaptor<GetCatalogRequest> captor = ArgumentCaptor.forClass(GetCatalogRequest.class);
        Mockito.verify(adminServiceStub).getCatalog(captor.capture());

        GetCatalogRequest request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
    }

    // ==================== ТЕСТЫ listTableRows ====================

    // ==================== ТЕСТЫ getProblematicOutboxEventsSummary ====================

    @Test
    @DisplayName("Должен вызвать gRPC getProblematicOutboxEventsSummary без serviceKey и вернуть отмапленный ответ")
    void shouldGetProblematicOutboxEventsSummary_withoutServiceKey() {
        // given
        GatewayContext context = createContext();

        GetProblematicOutboxEventsSummaryResponse grpcResponse =
                GetProblematicOutboxEventsSummaryResponse.newBuilder().build();
        ProblematicOutboxEventsSummaryResponseDto restResponse = new ProblematicOutboxEventsSummaryResponseDto();

        Mockito.when(adminServiceStub.getProblematicOutboxEventsSummary(
                        Mockito.any(GetProblematicOutboxEventsSummaryRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestProblematicOutboxEventsSummaryResponse(grpcResponse))
                .thenReturn(restResponse);

        // when & then
        StepVerifier.create(client.getProblematicOutboxEventsSummary(null, context))
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<GetProblematicOutboxEventsSummaryRequest> captor =
                ArgumentCaptor.forClass(GetProblematicOutboxEventsSummaryRequest.class);
        Mockito.verify(adminServiceStub).getProblematicOutboxEventsSummary(captor.capture());

        GetProblematicOutboxEventsSummaryRequest request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().hasServiceKey()).isFalse();
    }

    @Test
    @DisplayName("Должен вызвать gRPC getProblematicOutboxEventsSummary с serviceKey и вернуть отмапленный ответ")
    void shouldGetProblematicOutboxEventsSummary_withServiceKey() {
        // given
        GatewayContext context = createContext();

        GetProblematicOutboxEventsSummaryResponse grpcResponse =
                GetProblematicOutboxEventsSummaryResponse.newBuilder().build();
        ProblematicOutboxEventsSummaryResponseDto restResponse = new ProblematicOutboxEventsSummaryResponseDto();

        Mockito.when(adminServiceStub.getProblematicOutboxEventsSummary(
                        Mockito.any(GetProblematicOutboxEventsSummaryRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestProblematicOutboxEventsSummaryResponse(grpcResponse))
                .thenReturn(restResponse);

        // when & then
        StepVerifier.create(client.getProblematicOutboxEventsSummary(TEST_SERVICE, context))
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<GetProblematicOutboxEventsSummaryRequest> captor =
                ArgumentCaptor.forClass(GetProblematicOutboxEventsSummaryRequest.class);
        Mockito.verify(adminServiceStub).getProblematicOutboxEventsSummary(captor.capture());

        GetProblematicOutboxEventsSummaryRequest request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getServiceKey()).isEqualTo(TEST_SERVICE);
    }

    @Test
    @DisplayName("Должен пробросить gRPC ошибку при запросе problematic outbox events summary")
    void shouldPropagateGrpcError_whenGetProblematicOutboxEventsSummary() {
        // given
        GatewayContext context = createContext();

        Mockito.when(adminServiceStub.getProblematicOutboxEventsSummary(
                        Mockito.any(GetProblematicOutboxEventsSummaryRequest.class)))
                .thenReturn(Mono.error(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE)));

        // when & then
        StepVerifier.create(client.getProblematicOutboxEventsSummary(null, context))
                .expectError(io.grpc.StatusRuntimeException.class)
                .verify();
    }

    // ==================== ТЕСТЫ listTableRows ====================

    @Test
    @DisplayName("Должен вызвать gRPC listTableRows с equals фильтром")
    void shouldListTableRowsWithEqualsFilter() {
        // given
        GatewayContext context = createContext();

        Map<String, String> filters = new HashMap<>();
        filters.put("status", "active");

        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();
        ReadOnlyTableRowsResponseDto restResponse = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminServiceStub.listTableRows(Mockito.any(ListTableRowsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestListTableRowsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<ReadOnlyTableRowsResponseDto> result = client.listTableRows(
                TEST_SERVICE, TEST_TABLE, 1, 20, null, "asc", filters, context
        );

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        // Проверяем, что запрос сформирован правильно
        ArgumentCaptor<ListTableRowsRequest> captor = ArgumentCaptor.forClass(ListTableRowsRequest.class);
        Mockito.verify(adminServiceStub).listTableRows(captor.capture());

        ListTableRowsRequest request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getServiceKey()).isEqualTo(TEST_SERVICE);
        Assertions.assertThat(request.getBody().getTableName()).isEqualTo(TEST_TABLE);
        Assertions.assertThat(request.getBody().getPage()).isEqualTo(1);
        Assertions.assertThat(request.getBody().getPageSize()).isEqualTo(20);
        Assertions.assertThat(request.getBody().getFiltersMap()).containsEntry("status", "active");
    }

    @Test
    @DisplayName("Должен вызвать gRPC listTableRows с contains фильтром")
    void shouldListTableRowsWithContainsFilter() {
        // given
        GatewayContext context = createContext();

        Map<String, String> filters = new HashMap<>();
        filters.put("email.contains", "@test.com");

        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();
        ReadOnlyTableRowsResponseDto restResponse = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminServiceStub.listTableRows(Mockito.any(ListTableRowsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestListTableRowsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<ReadOnlyTableRowsResponseDto> result = client.listTableRows(
                TEST_SERVICE, TEST_TABLE, 1, 20, null, "asc", filters, context
        );

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<ListTableRowsRequest> captor = ArgumentCaptor.forClass(ListTableRowsRequest.class);
        Mockito.verify(adminServiceStub).listTableRows(captor.capture());

        ListTableRowsRequest request = captor.getValue();
        Assertions.assertThat(request.getBody().getFiltersMap()).containsEntry("email.contains", "@test.com");
    }

    @Test
    @DisplayName("Должен вызвать gRPC listTableRows с from и to фильтрами")
    void shouldListTableRowsWithFromAndToFilters() {
        // given
        GatewayContext context = createContext();

        Map<String, String> filters = new HashMap<>();
        filters.put("created_at.from", "2026-01-01T00:00:00Z");
        filters.put("created_at.to", "2026-12-31T23:59:59Z");

        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();
        ReadOnlyTableRowsResponseDto restResponse = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminServiceStub.listTableRows(Mockito.any(ListTableRowsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestListTableRowsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<ReadOnlyTableRowsResponseDto> result = client.listTableRows(
                TEST_SERVICE, TEST_TABLE, 1, 20, null, "asc", filters, context
        );

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<ListTableRowsRequest> captor = ArgumentCaptor.forClass(ListTableRowsRequest.class);
        Mockito.verify(adminServiceStub).listTableRows(captor.capture());

        ListTableRowsRequest request = captor.getValue();
        Assertions.assertThat(request.getBody().getFiltersMap())
                .containsEntry("created_at.from", "2026-01-01T00:00:00Z")
                .containsEntry("created_at.to", "2026-12-31T23:59:59Z");
    }

    @Test
    @DisplayName("Должен вызвать gRPC listTableRows со всеми фильтрами")
    void shouldListTableRowsWithAllFilters() {
        // given
        GatewayContext context = createContext();

        Map<String, String> filters = new HashMap<>();
        filters.put("status", "active");
        filters.put("email.contains", "@test.com");
        filters.put("created_at.from", "2026-01-01T00:00:00Z");
        filters.put("created_at.to", "2026-12-31T23:59:59Z");

        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();
        ReadOnlyTableRowsResponseDto restResponse = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminServiceStub.listTableRows(Mockito.any(ListTableRowsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestListTableRowsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<ReadOnlyTableRowsResponseDto> result = client.listTableRows(
                TEST_SERVICE, TEST_TABLE, 1, 20, "created_at", "desc", filters, context
        );

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<ListTableRowsRequest> captor = ArgumentCaptor.forClass(ListTableRowsRequest.class);
        Mockito.verify(adminServiceStub).listTableRows(captor.capture());

        ListTableRowsRequest request = captor.getValue();
        Assertions.assertThat(request.getBody().getSort()).isEqualTo("created_at");
        Assertions.assertThat(request.getBody().getOrder()).isEqualTo("desc");

        Assertions.assertThat(request.getBody().getFiltersMap())
                .containsEntry("status", "active")
                .containsEntry("email.contains", "@test.com")
                .containsEntry("created_at.from", "2026-01-01T00:00:00Z")
                .containsEntry("created_at.to", "2026-12-31T23:59:59Z");
    }

    @Test
    @DisplayName("Должен вызвать gRPC listTableRows без фильтров")
    void shouldListTableRowsWithNullFilters() {
        // given
        GatewayContext context = createContext();

        ListTableRowsResponse grpcResponse = ListTableRowsResponse.newBuilder().build();
        ReadOnlyTableRowsResponseDto restResponse = new ReadOnlyTableRowsResponseDto();

        Mockito.when(adminServiceStub.listTableRows(Mockito.any(ListTableRowsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(mapper.toRestListTableRowsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        Mono<ReadOnlyTableRowsResponseDto> result = client.listTableRows(
                TEST_SERVICE, TEST_TABLE, null, null, null, null, null, context
        );

        // then
        StepVerifier.create(result)
                .expectNext(restResponse)
                .verifyComplete();

        ArgumentCaptor<ListTableRowsRequest> captor = ArgumentCaptor.forClass(ListTableRowsRequest.class);
        Mockito.verify(adminServiceStub).listTableRows(captor.capture());

        ListTableRowsRequest request = captor.getValue();
        Assertions.assertThat(request.getBody().hasPage()).isFalse();
        Assertions.assertThat(request.getBody().hasPageSize()).isFalse();
        Assertions.assertThat(request.getBody().hasSort()).isFalse();
        Assertions.assertThat(request.getBody().hasOrder()).isFalse();
        Assertions.assertThat(request.getBody().getFiltersMap()).isEmpty();
    }
}