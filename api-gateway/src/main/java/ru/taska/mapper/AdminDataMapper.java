package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.*;
import ru.taska.domain.dto.*;

import java.util.*;

import com.google.protobuf.Timestamp;
import ru.taska.domain.dto.ProblematicOutboxEventDto;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

/**
 * Основные методы-маперы toRestGetCatalogResponse и toRestListTableRowsResponse
 * Catalog включает в себя Service.
 * Service включает Table.
 * Table включает Column
 */

@Component
public class AdminDataMapper {

    /**
     * Преобразует gRPC GetCatalogResponse → REST MetadataResponse
     */
    public MetadataResponse toRestGetCatalogResponse(GetCatalogResponse grpcCatalogResponse) {
        if (grpcCatalogResponse == null || !grpcCatalogResponse.hasCatalog()) {
            return new MetadataResponse();
        }
        List<ServiceMetadataDto> serviceMetadata = grpcCatalogResponse.getCatalog()
                .getServicesList()
                .stream()
                .map(this::toRestServiceMetadataDto) /// список serviceMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        MetadataResponse response = new MetadataResponse();
        response.setServices(serviceMetadata);
        return response;
    }

    /**
     * Преобразует gRPC ServiceMetadata → REST ServiceMetadataDto
     */
    private ServiceMetadataDto toRestServiceMetadataDto(ServiceMetadata grpcServiceMetadata) {
        ServiceMetadataDto dto = new ServiceMetadataDto();
        dto.setName(grpcServiceMetadata.getServiceKey());
        dto.setDatabaseAlias(grpcServiceMetadata.getAlias());

        List<TableMetadataDto> tables = grpcServiceMetadata.getTablesList()
                .stream()
                .map(this::toTableMetadataDto) /// список tableMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        dto.setTables(tables);
        return dto;
    }

    private TableMetadataDto toTableMetadataDto(TableMetadata grpcTableMetadata) {
        TableMetadataDto dto = new TableMetadataDto();
        dto.setName(grpcTableMetadata.getName());

        /// Преобразуем список колонок в рестовый список колонок
        List<ColumnMetadataDto> columns = grpcTableMetadata.getColumnsList()
                .stream()
                .map(this::toRestColumnMetadataDto) /// список columnMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        dto.setColumns(columns);

        /// Находим primary key
        grpcTableMetadata.getColumnsList().stream()
                .filter(ColumnMetadata::getPrimaryKey) /// находим колонки, где pk = true
                .findFirst()
                .ifPresent(pk -> dto.setPrimaryKey(pk.getName()));
        return dto;
    }

    /**
     * Преобразует gRPC ColumnMetadata → REST ColumnMetadataDto
     */
    private ColumnMetadataDto toRestColumnMetadataDto(ColumnMetadata grpcColumn) {
        ColumnMetadataDto dto = new ColumnMetadataDto();
        dto.setName(grpcColumn.getName());
        dto.setType(grpcColumn.getType());
        dto.setSensitive(grpcColumn.getSensitive());
        return dto;
    }

    /**
     * Преобразует gRPC ListTableRowsResponse → REST ReadOnlyTableRowsResponseDto
     */
    public ReadOnlyTableRowsResponseDto toRestListTableRowsResponse(ListTableRowsResponse grpcResponse) {
        ReadOnlyTableRowsResponseDto dto = new ReadOnlyTableRowsResponseDto();

        List<Map<String, Object>> data = grpcResponse.getRowsList()
                .stream()
                .map(this::rowToMap)
                .collect(Collectors.toList());
        dto.setData(data);

        PaginationInfoDto pagination = new PaginationInfoDto();
        pagination.setCurrentPage(grpcResponse.getPagination().getCurrentPage());
        pagination.setPageSize(grpcResponse.getPagination().getPageSize());
        pagination.setTotalRows(grpcResponse.getPagination().getTotalRows());
        pagination.setTotalPages(grpcResponse.getPagination().getTotalPages());
        pagination.setHasNext(grpcResponse.getPagination().getHasNext());
        pagination.setHasPrev(grpcResponse.getPagination().getHasPrev());
        dto.setPagination(pagination);

        TableCapabilitiesDto meta = new TableCapabilitiesDto();
        meta.setService(grpcResponse.getMeta().getServiceKey());
        meta.setTable(grpcResponse.getMeta().getTableName());
        meta.setColumns(grpcResponse.getMeta().getColumnsList());
        meta.setSortableColumns(grpcResponse.getMeta().getSortableColumnsList());
        meta.setFilterableColumns(grpcResponse.getMeta().getFilterableColumnsList());
        dto.setMeta(meta);

        return dto;
    }

    /**
     * Преобразует gRPC GetTableRowByIdResponse → REST ReadOnlySingleRowResponseDto
     */
    public ReadOnlySingleRowResponseDto toRestGetTableRowByIdResponse(
            ru.taska.api.admin.v1.GetTableRowByIdResponse grpcResponse) {
        ReadOnlySingleRowResponseDto dto = new ReadOnlySingleRowResponseDto();
        if (grpcResponse.hasRow()) {
            dto.setData(rowToMap(grpcResponse.getRow()));
        }
        return dto;
    }

    /**
     * Преобразует gRPC GetProblematicOutboxEventsSummaryResponse → REST ProblematicOutboxEventsSummaryResponseDto
     */
    public ProblematicOutboxEventsSummaryResponseDto toRestProblematicOutboxEventsSummaryResponse(
            GetProblematicOutboxEventsSummaryResponse grpcResponse) {
        ProblematicOutboxEventsSummaryResponseDto dto = new ProblematicOutboxEventsSummaryResponseDto();

        dto.setEvents(grpcResponse.getEventsList().stream()
                .map(this::toRestProblematicOutboxEventDto)
                .collect(Collectors.toList()));

        dto.setCounts(grpcResponse.getCountsList().stream()
                .map(this::toRestProblematicEventCountsByServiceDto)
                .collect(Collectors.toList()));

        dto.setNotAllShown(grpcResponse.getNotAllShown());

        return dto;
    }

    private ProblematicOutboxEventDto toRestProblematicOutboxEventDto(
            ru.taska.api.admin.v1.ProblematicOutboxEventDto grpcEvent) {
        ProblematicOutboxEventDto dto = new ProblematicOutboxEventDto();
        dto.setId(grpcEvent.getId());
        dto.setAggregateType(grpcEvent.getAggregateType());
        dto.setAggregateId(grpcEvent.getAggregateId());
        dto.setEventType(grpcEvent.getEventType());
        dto.setPayload(grpcEvent.getPayload());
        dto.setStatus(grpcEvent.getStatus());
        dto.setCreatedAt(toOffsetDateTime(grpcEvent.getCreatedAt()));
        if (grpcEvent.hasPublishedAt()) {
            dto.setPublishedAt(toOffsetDateTime(grpcEvent.getPublishedAt()));
        }
        dto.setAttempts(grpcEvent.getAttempts());
        if (grpcEvent.hasLastErrorMessage()) {
            dto.setLastErrorMessage(grpcEvent.getLastErrorMessage());
        }
        if (grpcEvent.hasProcessingStartedAt()) {
            dto.setProcessingStartedAt(toOffsetDateTime(grpcEvent.getProcessingStartedAt()));
        }
        if (grpcEvent.hasRequestId()) {
            dto.setRequestId(grpcEvent.getRequestId());
        }
        dto.setServiceKey(grpcEvent.getServiceKey());
        dto.setReason(grpcEvent.getReason());
        return dto;
    }

    private ProblematicEventCountsByServiceDto toRestProblematicEventCountsByServiceDto(
            ProblematicEventCountsByService grpcCounts) {
        ProblematicEventCountsByServiceDto dto = new ProblematicEventCountsByServiceDto();
        dto.setServiceKey(grpcCounts.getServiceKey());
        dto.setOverdueNewCount(grpcCounts.getOverdueNewCount());
        dto.setStuckProcessingCount(grpcCounts.getStuckProcessingCount());
        dto.setFailedCount(grpcCounts.getFailedCount());
        return dto;
    }

    /**
     * Преобразует gRPC Row → Map<String, Object>
     */
    private Map<String, Object> rowToMap(Row grpcRow) {
        Map<String, Object> map = new HashMap<>();
        for (var entry : grpcRow.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), convertValue(entry.getValue()));
        }
        return map;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    /**
     * Преобразует gRPC Value → Java Object
     */
    private Object convertValue(Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case TIMESTAMP_VALUE -> Instant.ofEpochSecond(value.getTimestampValue());
            default -> null;
        };
    }

    /**
     * Преобразует gRPC-ответ ручного retry outbox-события
     * в REST DTO API Gateway.
     *
     * @param grpcResponse ответ admin-service
     * @return REST DTO состояния события после retry
     */
    public RetryOutboxEventResponseDto toRestRetryOutboxEventResponse(
            RetryOutboxEventResponse grpcResponse
    ) {
        RetryOutboxEventResponseDto dto =
                new RetryOutboxEventResponseDto();

        dto.setEventId(UUID.fromString(grpcResponse.getEventId()));
        dto.setStatus(grpcResponse.getStatus());
        dto.setAttempts(grpcResponse.getAttempts());

        return dto;
    }

    /**
     * Преобразует gRPC ListAuditEntriesResponse → REST ListAuditEntriesResponseDto
     */
    public ListAuditEntriesResponseDto toRestListAuditEntriesResponse(ListAuditEntriesResponse grpcResponse) {
        if (grpcResponse == null) {
            return new ListAuditEntriesResponseDto();
        }

        List<AuditEntryDto> entries = grpcResponse.getEntriesList()
                .stream()
                .map(this::toRestAuditEntry)
                .collect(Collectors.toList());

        ListAuditEntriesResponseDto response = new ListAuditEntriesResponseDto();
        response.setEntries(entries);

        PaginationInfoDto pagination = new PaginationInfoDto();
        pagination.setCurrentPage(grpcResponse.getCurrentPage());
        pagination.setPageSize(grpcResponse.getPageSize());
        pagination.setTotalRows(grpcResponse.getTotalRows());
        pagination.setTotalPages(grpcResponse.getTotalPages());
        pagination.setHasNext(grpcResponse.getHasNext());
        pagination.setHasPrev(grpcResponse.getHasPrev());

        response.setPagination(pagination);

        return response;
    }

    /**
     * /**
     * Преобразует gRPC ListAuditEntry → REST AuditEntryDto
     */
    private AuditEntryDto toRestAuditEntry(ListAuditEntry grpcAuditEntry) {
        AuditEntryDto dto = new AuditEntryDto();

        if (grpcAuditEntry.hasActorUserId()) {
            dto.setActorUserId(UUID.fromString(grpcAuditEntry.getActorUserId()));
        }
        if (grpcAuditEntry.hasActorLogin()) {
            dto.setActorLogin(grpcAuditEntry.getActorLogin());
        }
        if (grpcAuditEntry.hasAction()) {
            dto.setAction(grpcAuditEntry.getAction());
        }
        if (grpcAuditEntry.hasTargetService()) {
            dto.setTargetService(grpcAuditEntry.getTargetService());
        }
        if (grpcAuditEntry.hasTargetTable()) {
            dto.setTargetTable(grpcAuditEntry.getTargetTable());
        }
        if (grpcAuditEntry.hasTargetId()) {
            dto.setTargetId(grpcAuditEntry.getTargetId());
        }
        if (grpcAuditEntry.hasOldValue()) {
            dto.setOldValue(grpcAuditEntry.getOldValue());
        }
        if (grpcAuditEntry.hasNewValue()) {
            dto.setNewValue(grpcAuditEntry.getNewValue());
        }
        if (grpcAuditEntry.hasReason()) {
            dto.setReason(grpcAuditEntry.getReason());
        }
        if (grpcAuditEntry.hasRequestId()) {
            dto.setRequestId(grpcAuditEntry.getRequestId());
        }
        if (grpcAuditEntry.hasCreatedAt()) {
            dto.setCreatedAt(OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(
                            grpcAuditEntry.getCreatedAt().getSeconds(),
                            grpcAuditEntry.getCreatedAt().getNanos()
                    ),
                    ZoneOffset.UTC
            ));
        }

        return dto;
    }
}
