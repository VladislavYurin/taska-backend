package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.AdminService;
import ru.taska.service.MetadataService;
import ru.taska.service.ReadOnlyQueryBuilder;
import ru.taska.service.SensitiveColumnMaskService;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ReadOnlyQueryBuilder queryBuilder;
    private final SensitiveColumnMaskService maskService;
    private final MetadataService metadataService;
    private final ReadOnlyRepository readOnlyRepository;

    @Override
    public Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto) {
        return Mono.defer(() -> {
            String serviceKey = requestDto.serviceKey(); // "auth"
            String tableName = requestDto.tableName();  // "users/..."
            int page = requestDto.page();
            int pageSize = requestDto.pageSize();
            String sort = requestDto.sort();
            String order = requestDto.order();
            Map<String, FilterOperatorsDto> filters = requestDto.filters();

            /// Построение безопасного SQL запроса (с проверкой allowlist)
            ReadOnlyQueryBuilder.SqlQuery safeQuery = queryBuilder.buildSafeQuery(
                    serviceKey, tableName, page, pageSize, sort, order, filters
            );
            /// Строим COUNT запрос (для пагинации) (allowlist для таблицы всегда проверяется в buildSafeQuery)
            ReadOnlyQueryBuilder.SqlQuery safeCountQuery = queryBuilder.buildSafeCountQuery(tableName, filters
            );

            /// Выполнение запроса к БД через DatabaseClient (параметризованный!)
            // ReadOnlyRepository:
            // - Берет DatabaseClient из ReadonlyR2dbcConfig
            // - Выполняет параметризованный запрос
            // - Возвращает Flux<Map<String, Object>> - строки таблицы
            return readOnlyRepository.executeQuery(serviceKey,safeQuery)
                    .collectList()
                    .flatMap(rows -> {
                        ///Считаем общее количество записей (нужно для пагинации)
                        return readOnlyRepository.countRows(serviceKey, safeCountQuery)
                                .flatMap(total->{
                                    /// Маскировка sensitive колонок
                                    List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(
                                            rows,           // строки из БД
                                            serviceKey,     // "user-service"
                                            tableName       // "users"
                                    );

                                    /// Получаем список колонок и создаем
                                    return metadataService.getTableColumns(serviceKey,tableName)
                                            .map(allColumns->
                                                    new ListTableRowsResponseDto(
                                                            maskedRows,
                                                            total,
                                                            page,
                                                            pageSize,
                                                            allColumns,
                                                            serviceKey,
                                                            tableName
                                                    )
                                            );
                                });
                    });
        });
    }

}
