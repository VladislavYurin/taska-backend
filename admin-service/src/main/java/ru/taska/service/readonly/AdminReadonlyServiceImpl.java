package ru.taska.service.readonly;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.domain.DbColumnType;
import ru.taska.domain.PageResult;

import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.dto.FilterAuditDTO;

import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReadonlyServiceImpl implements AdminReadonlyService {

    private final MetadataCatalogProperties catalogProperties;
    private final SensitiveDataMaskService maskService;
    private final MetadataService metadataService;
    private final ReadOnlyRepository readOnlyRepository;
    private final ReadOnlyQueryBuilder queryBuilder;
    private final FilterParser filterParser;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto, String requestId, String nodeId) {
        return Mono.defer(() -> {
            log.debug("listTableRows request: service='{}', table='{}', page={}, pageSize={}, sort='{}', order='{}'",
                    requestDto.serviceKey(), requestDto.tableName(), requestDto.page(),
                    requestDto.pageSize(), requestDto.sort(), requestDto.order());

            String serviceKey = requestDto.serviceKey();
            String tableName = requestDto.tableName();
            String schema = resolveSchema(serviceKey);

            int page = normalizePage(requestDto.page());
            int pageSize = normalizePageSize(requestDto.pageSize());
            String sort = requestDto.sort();
            String order = requestDto.order();
            Map<String, FilterOperatorsDto> filters = filterParser.parse(requestDto.filters());

            return Mono.zip(
                    metadataService.getTableColumns(serviceKey, tableName),
                    metadataService.getPrimaryKeyColumn(serviceKey, tableName)
            ).flatMap(tuple -> {
                Map<String, DbColumnType> columnTypes = tuple.getT1();
                String primaryKeyColumn = tuple.getT2();

                PageableListQueries safeQuery = queryBuilder.buildSafePageableListQueries(
                        serviceKey, schema, tableName, page, pageSize, sort, order, filters, columnTypes, primaryKeyColumn
                );

                List<String> columnNames = List.copyOf(columnTypes.keySet());

                return readOnlyRepository.executeQuery(serviceKey, safeQuery.selectQuery().parameterizedQuery(), safeQuery.selectQuery().params())
                        .collectList()
                        .flatMap(rows ->
                                readOnlyRepository.countRows(serviceKey, safeQuery.countQuery().parameterizedQuery(), safeQuery.countQuery().params())
                                        .map(total -> {
                                            List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(
                                                    rows, serviceKey, tableName, requestId, nodeId
                                            );
                                            return new ListTableRowsResponseDto(
                                                    maskedRows, total, page, pageSize,
                                                    columnNames, serviceKey, tableName
                                            );
                                        })
                        );
            });
        });
    }

    @Override
    public Mono<GetTableRowByIdResponseDto> getTableRowById(GetTableRowByIdRequestDto requestDto, String requestId, String nodeId) {
        return Mono.defer(() -> {
            String serviceKey = requestDto.serviceKey();
            String tableName = requestDto.tableName();
            String schema = resolveSchema(serviceKey);
            String id = requestDto.id();

            log.debug("getTableRowById request: service='{}', table='{}', id='{}'", serviceKey, tableName, id);

            return metadataService.getPrimaryKeyColumn(serviceKey, tableName)
                    .flatMap(pkColumn -> {
                        SqlQuery safeQuery = queryBuilder.buildSafeGetByIdQuery(
                                serviceKey, schema, tableName, pkColumn, id
                        );

                        return readOnlyRepository.executeQuery(serviceKey, safeQuery.parameterizedQuery(), safeQuery.params())
                                .next()
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Row not found: {}.{} = {}", tableName, pkColumn, id);
                                    return Mono.error(new DomainException(
                                            DomainStatus.NOT_FOUND,
                                            "Row not found: " + tableName + "." + pkColumn + " = " + id));
                                }))
                                .map(row -> {
                                    Map<String, Object> maskedRow = maskService.maskSensitiveData(
                                            List.of(row), serviceKey, tableName, requestId, nodeId
                                    ).getFirst();
                                    return new GetTableRowByIdResponseDto(maskedRow);
                                });
                    });
        });
    }

    @Override
    public Mono<PageResult<AuditEntriesResponseDto>> listAuditEntries(FilterAuditDTO filterAuditDTO, Integer page, Integer pageSize) {
        int resolvedPage = normalizePage(page);
        int resolvedPageSize = normalizePageSize(pageSize);
        long resolvedOffset = (long) resolvedPage * resolvedPageSize;

        log.debug("Fetching audit entries with filters - actor: {}, action: {}, target: {}/{}/{}, requestId: {}, from: {}, to: {}, page: {}, size: {}",
                filterAuditDTO != null ? filterAuditDTO.actorUserId() : null,
                filterAuditDTO != null ? filterAuditDTO.action() : null,
                filterAuditDTO != null ? filterAuditDTO.targetService() : null,
                filterAuditDTO != null ? filterAuditDTO.targetTable() : null,
                filterAuditDTO != null ? filterAuditDTO.targetId() : null,
                filterAuditDTO != null ? filterAuditDTO.requestId() : null,
                filterAuditDTO != null ? filterAuditDTO.createdAtFrom() : null,
                filterAuditDTO != null ? filterAuditDTO.createdAtTo() : null,
                resolvedPage,
                resolvedPageSize);

        FilterAuditDTO safeFilter = filterAuditDTO != null ? filterAuditDTO : FilterAuditDTO.builder().build();

        return Mono.zip(
                         auditLogRepository.findByFilter(safeFilter, resolvedPageSize, resolvedOffset)
                                 .collectList(),
                         auditLogRepository.countByFilter(safeFilter)
                 )
                 .map(tuple -> {
                     List<AuditLog> auditLogs = tuple.getT1();
                     Long count = tuple.getT2();

                     List<AuditEntriesResponseDto> maskedLogs = auditLogs.stream()
                             .map(this::maskAndMapAuditLog)
                             .toList();

                     log.debug("Found {} audit entries out of {} total", maskedLogs.size(), count);
                     return new PageResult<>(maskedLogs, count, resolvedPage, resolvedPageSize);
                })
                .onErrorResume(ex -> {
                    log.error("Error fetching audit entries", ex);
                    return Mono.error(ex);
                });
    }

    /**
     * Маскирует sensitive-данные в old/new значениях аудит-лога и преобразует в DTO.
     */
    private AuditEntriesResponseDto maskAndMapAuditLog(AuditLog auditLog) {
        Map<String, Object> oldValue = convertJsonNodeToMap(auditLog.getOldValue());
        Map<String, Object> newValue = convertJsonNodeToMap(auditLog.getNewValue());

        Map<String, Object> maskedOldValue = oldValue != null
                ? maskService.maskSensitiveDataForAudit(
                oldValue,
                auditLog.getTargetService(),
                auditLog.getTargetTable(),
                auditLog.getRequestId(),
                "audit-log-read"
        )
                : null;

        Map<String, Object> maskedNewValue = newValue != null
                ? maskService.maskSensitiveDataForAudit(
                newValue,
                auditLog.getTargetService(),
                auditLog.getTargetTable(),
                auditLog.getRequestId(),
                "audit-log-read"
        )
                : null;

        auditLog.setOldValue(convertMapToJsonNode(maskedOldValue));
        auditLog.setNewValue(convertMapToJsonNode(maskedNewValue));

        return auditLogMapper.toResponseDto(auditLog);
    }

    private Map<String, Object> convertJsonNodeToMap(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull() || jsonNode.isMissingNode()) {
            return null;
        }
        if (!jsonNode.isObject()) {
            log.warn("Expected JSON object but got: {}", jsonNode.getNodeType());
            return null;
        }
        return objectMapper.convertValue(jsonNode, new TypeReference<Map<String, Object>>() {
        });
    }

    private JsonNode convertMapToJsonNode(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return objectMapper.valueToTree(map);
    }

    private int normalizePage(Integer page) {
        int defaultPage = catalogProperties.pagination().defaultPage();
        if (page != null && page < 0) {
            log.warn("Invalid page value: {}. Falling back to default: {}", page, defaultPage);
            return defaultPage;
        }
        return (page == null) ? defaultPage : page;
    }

    private int normalizePageSize(Integer pageSize) {
        int defaultPageSize = catalogProperties.pagination().defaultPageSize();
        int maxPageSize = catalogProperties.pagination().maxPageSize();
        if (pageSize != null && pageSize < 1) {
            log.warn("Invalid pageSize value: {}. Falling back to default: {}", pageSize, defaultPageSize);
            return defaultPageSize;
        }
        if (pageSize != null && pageSize > maxPageSize) {
            log.warn("Requested pageSize {} exceeds maximum {}. Clamping to max", pageSize, maxPageSize);
            return maxPageSize;
        }
        return (pageSize == null) ? defaultPageSize : pageSize;
    }

    private String resolveSchema(String serviceKey) {
        MetadataCatalogProperties.ServiceProperties serviceProps = catalogProperties.services().get(serviceKey);
        if (serviceProps == null) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Unknown service: " + serviceKey);
        }
        return serviceProps.schema();
    }
}


