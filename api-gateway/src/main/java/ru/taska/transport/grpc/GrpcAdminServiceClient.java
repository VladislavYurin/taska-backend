package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequestBody;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdRequestBody;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BlockUserRequestDto;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ProblematicOutboxEventsSummaryResponseDto;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.domain.dto.ResetLockoutRequestDto;
import ru.taska.domain.dto.UnblockUserRequestDto;
import ru.taska.domain.dto.UserStatusResponseDto;
import ru.taska.mapper.AdminDataMapper;
import ru.taska.mapper.AdminUserManagementMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAdminServiceClient {

    private final ReactorAdminServiceGrpc.ReactorAdminServiceStub adminServiceStub;
    private final AdminDataMapper adminDataMapper;
    private final AdminUserManagementMapper adminUserManagementMapper;
    private final GrpcClientProperties properties;

    public Mono<MetadataResponse> getCatalog(GatewayContext context){

        log.debug("[{}] Calling getCatalog", context.requestId());

        GetCatalogRequest getCatalogRequest = GetCatalogRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .build();
        return dynamicStub().getCatalog(getCatalogRequest)
                .map(adminDataMapper::toRestGetCatalogResponse);
    }

    public Mono<ReadOnlyTableRowsResponseDto> listTableRows(
            String service,
            String table,
            Integer page,
            Integer pageSize,
            String sort,
            String order,
            Map<String, String> filters,
            GatewayContext context
    ) {
        log.debug("[{}] Calling listTableRows", context.requestId());

        ListTableRowsRequestBody.Builder bodyBuilder = ListTableRowsRequestBody.newBuilder()
                .setServiceKey(service)
                .setTableName(table);

        if (page != null) bodyBuilder.setPage(page);
        if (pageSize != null) bodyBuilder.setPageSize(pageSize);
        if (sort != null) bodyBuilder.setSort(sort);
        if (order != null) bodyBuilder.setOrder(order);
        if (filters != null && !filters.isEmpty()) bodyBuilder.putAllFilters(filters);

        ListTableRowsRequestBody listTableRowsRequestBody = bodyBuilder.build();

        ListTableRowsRequest listTableRowsRequest = ListTableRowsRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(listTableRowsRequestBody)
                .build();

        return dynamicStub().listTableRows(listTableRowsRequest)
                .map(adminDataMapper::toRestListTableRowsResponse);
    }

    public Mono<ReadOnlySingleRowResponseDto> getTableRowById(
            String service,
            String table,
            UUID id,
            GatewayContext context
    ) {
        log.debug("[{}] Calling getTableRowById", context.requestId());

        GetTableRowByIdRequestBody body = GetTableRowByIdRequestBody.newBuilder()
                .setServiceKey(service)
                .setTableName(table)
                .setId(id.toString())
                .build();

        GetTableRowByIdRequest request = GetTableRowByIdRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(body)
                .build();

        return dynamicStub().getTableRowById(request)
                .map(adminDataMapper::toRestGetTableRowByIdResponse);
    }

    public Mono<ProblematicOutboxEventsSummaryResponseDto> getProblematicOutboxEventsSummary(
            String serviceKey,
            GatewayContext context
    ) {
        log.debug("[{}] Calling getProblematicOutboxEventsSummary", context.requestId());

        var bodyBuilder =
                GetProblematicOutboxEventsSummaryRequestBody.newBuilder();
        if (serviceKey != null) {
            bodyBuilder.setServiceKey(serviceKey);
        }

        var request = GetProblematicOutboxEventsSummaryRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(bodyBuilder.build())
                .build();

        return dynamicStub().getProblematicOutboxEventsSummary(request)
                .map(adminDataMapper::toRestProblematicOutboxEventsSummaryResponse);
    }

    public Mono<UserStatusResponseDto> blockUser(
            UUID userId,
            Mono<BlockUserRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling blockUser", context.requestId());

        return request
                .flatMap(requestDto->
                        dynamicStub().blockUser(
                                adminUserManagementMapper.toBlockUserGrpcRequest(userId,requestDto,context)
                        )
                )
                .map(adminUserManagementMapper::toRestUserStatusResponse);
    }


    public Mono<UserStatusResponseDto> unblockUser(
            UUID userId,
            Mono<UnblockUserRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling unblockUser", context.requestId());

        return request
                .flatMap(requestDto->
                        dynamicStub().unblockUser(
                                adminUserManagementMapper.toUnblockUserGrpcRequest(userId,requestDto,context)
                        )
                )
                .map(adminUserManagementMapper::toRestUserStatusResponse);
    }

    public Mono<UserStatusResponseDto> resetCredentialLockout(
            UUID userId,
            Mono<ResetLockoutRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling resetCredentialLockout", context.requestId());

        return request
                .flatMap(requestDto->
                        dynamicStub().resetCredentialLockout(
                                adminUserManagementMapper.toResetCredentialLockoutRequest(userId,requestDto,context)
                        )
                )
                .map(adminUserManagementMapper::toRestUserStatusResponse);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorAdminServiceGrpc.ReactorAdminServiceStub dynamicStub() {
        return adminServiceStub.withDeadlineAfter(properties.adminService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
