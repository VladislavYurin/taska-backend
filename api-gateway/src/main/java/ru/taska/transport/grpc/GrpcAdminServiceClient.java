package ru.taska.transport.grpc;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.FilterOperators;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;

import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ReadOnlyResponseDto;
import ru.taska.mapper.AdminDataMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAdminServiceClient {

    private final ReactorAdminServiceGrpc.ReactorAdminServiceStub adminServiceStub;
    private final AdminDataMapper mapper;
    private final GrpcClientProperties properties;

    public Mono<MetadataResponse> getCatalog(GatewayContext context){

        log.info("[{}] Calling getCatalog", context.requestId());

        GetCatalogRequest getCatalogRequest = GetCatalogRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .build();
        return dynamicStub().getCatalog(getCatalogRequest)
                .map(mapper::toRestGetCatalogResponse);
    }

    public Mono<ReadOnlyResponseDto> listTableRows(
            String service,
            String table,
            Integer page,
            Integer pageSize,
            @Nullable String sort,
            String order,
            @Nullable Map<String, String> filters, // "status.eq=ACTIVE"  -> "status.eq" , "ACTIVE"
            GatewayContext context
    ) {
        log.info("[{}] Calling listTableRows", context.requestId());


        ListTableRowsRequestBody listTableRowsRequestBody = ListTableRowsRequestBody.newBuilder()
                .setServiceKey(service)
                .setTableName(table)
                .setPage(page != null ? page : 1) // Параметры могут прийти null, обрабатываем вручную
                .setPageSize(pageSize != null  ? pageSize : 20) // А если придут невалидные данные - обрабатываем в квери билдере
                .setSort(sort != null ? sort : "")
                .setOrder(order != null ? order : "asc")
                .putAllFilters(filters != null && !filters.isEmpty() ? filtersToFilterOperators(filters) : Collections.emptyMap())
                .build();

        ListTableRowsRequest listTableRowsRequest = ListTableRowsRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(listTableRowsRequestBody)
                .build();

        return dynamicStub().listTableRows(listTableRowsRequest)
                .map(mapper::toRestListTableRowsResponse);
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

    /**
     * Преобразует Map<String, String> в Map<String, FilterOperators>.
     *
     * Входные данные (ключи в формате "column.operator"):
     *   "status.eq" , "active" или "status" , "active
     *   "email.contains" , "@test.com"
     *   "created_at.from" , "2026-01-01T00:00:00Z"
     *   "created_at.to" , "2026-12-31T23:59:59Z"
     *
     * Выходные данные:
     *   "status" , FilterOperators(equals="active")
     *   "email" ,  FilterOperators(contains="@test.com")
     *   "created_at" , FilterOperators(from="2026-01-01T00:00:00Z", to="2026-12-31T23:59:59Z")
     */
    private static final Pattern FILTER_PATTERN = Pattern.compile("^([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9]+)$");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Set<String> VALID_OPERATORS = Set.of("eq", "equals", "contains", "from", "to");

    private Map<String, FilterOperators> filtersToFilterOperators(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Collections.emptyMap();
        }
        log.debug("Processing filters: {}", filters);

        Map<String, FilterOperators.Builder> buildersMap = new HashMap<>();

        for (var entry : filters.entrySet()) {
            String columnOperator = entry.getKey();
            String value = entry.getValue();

            // Сначала проверяем, что ключ содержит только разрешенные символы
            // Защита от явных SQL инъекций
            if (!columnOperator.matches("^[a-zA-Z0-9_.-]+$")) {
                log.warn("Invalid filter columnOperator format '{}', skipping", columnOperator);
                continue;
            }

            String column;
            String operator;

            Matcher matcher = FILTER_PATTERN.matcher(columnOperator);
            if (matcher.matches()) {
                // Вариант с оператором (например, email.contains)
                column = matcher.group(1);
                operator = matcher.group(2);
            } else {
                // Вариант без оператора (например, status=active) -> трактуем как точное совпадение
                column = columnOperator;
                operator = "equals";
                log.debug("No operator found for '{}', using 'equals'", columnOperator);  // ← добавить
            }

            // Проверяем имя колонки (защита от SQL инъекций)
            if (!COLUMN_PATTERN.matcher(column).matches()) {
                log.warn("Invalid column name format '{}', skipping", column);
                continue;
            }

            // Проверяем оператор (должен быть известным)
            if (!VALID_OPERATORS.contains(operator)) {
                log.warn("Unknown operator '{}' for column '{}', skipping", operator, column);
                continue;
            }

            FilterOperators.Builder builder = buildersMap.computeIfAbsent(column, k -> FilterOperators.newBuilder());

            switch (operator) {
                case "eq":
                case "equals":
                    builder.setEquals(value);
                    break;
                case "contains":
                    builder.setContains(value);
                    break;
                case "from":
                    builder.setFrom(value);
                    break;
                case "to":
                    builder.setTo(value);
                    break;
            }
        }
        Map<String, FilterOperators> grpcFilters = new HashMap<>();
        buildersMap.forEach((column, builder) -> grpcFilters.put(column, builder.build()));
        return grpcFilters;
    }
}
