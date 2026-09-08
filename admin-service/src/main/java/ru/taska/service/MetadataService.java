package ru.taska.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.config.props.MetadataCatalogProperties.ServiceProperties;
import ru.taska.config.props.MetadataCatalogProperties.TableProperties;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.DbColumnType;
import ru.taska.domain.PrimaryKeyMetadata;
import ru.taska.dto.CatalogDto;
import ru.taska.dto.ColumnDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.MetadataSchemaRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataService {

    private final MetadataCatalogProperties properties;
    private final MetadataSchemaRepository schemaRepository;

    /**
     * Fail-fast: набор клиентов статичен и известен на старте, поэтому проверяем
     * рассинхрон {@code admin.metadata.services.*} и зарегистрированных клиентов
     * один раз при инициализации, а не на каждом запросе.
     */
    @PostConstruct
    void validateClientsConfigured() {
        Set<String> registered = schemaRepository.registeredServiceKeys();

        Set<String> missing = properties.services().keySet().stream()
                .filter(serviceKey -> !registered.contains(serviceKey))
                .collect(Collectors.toCollection(TreeSet::new));

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "No readonly DatabaseClient registered for service keys: " + missing
                            + ". Available clients: " + new TreeSet<>(registered)
            );
        }
    }

    /**
     * Собирает каталог таблиц для всех сервисов, перечисленных в {@code admin.metadata.services}.
     */
    public Mono<CatalogDto> getCatalog() {
        return Flux.fromIterable(properties.services().entrySet())
                .flatMap(entry -> buildServiceDto(entry.getKey(), entry.getValue()))
                .collectList()
                .map(CatalogDto::new);
    }

    /**
     * Строит описание одного сервиса: alias + список таблиц.
     */
    private Mono<ServiceDto> buildServiceDto(
            String serviceKey,
            ServiceProperties props
    ) {
        String schema = props.schema();
        TableProperties tableProps = props.tables();

        return schemaRepository.findColumns(serviceKey, schema)
                .collectList()
                .flatMap(columns ->
                        schemaRepository.findPrimaryKeys(serviceKey, schema)
                                .collectList()
                                .map(pks -> buildTables(columns, pks, tableProps))
                )
                .map(tables -> new ServiceDto(serviceKey, props.alias(), tables));
    }

    /**
     * Преобразует метаданные колонок и PK из {@code information_schema} в DTO каталога
     * с применением правил allow/deny/sensitive из конфигурации.
     */
    static List<TableDto> buildTables(
            List<ColumnMetadata> columns,
            List<PrimaryKeyMetadata> primaryKeys,
            TableProperties tableProps
    ) {
        Set<String> allow = Set.copyOf(tableProps.allow());
        Set<String> deny = Set.copyOf(tableProps.deny());
        Set<String> sensitiveColumns = tableProps.sensitiveColumns() != null
                ? tableProps.sensitiveColumns().keySet()
                : Set.of();

        Set<String> pkKeys = primaryKeys.stream()
                .map(pk -> pk.tableName() + "." + pk.columnName())
                .collect(Collectors.toSet());

        Map<String, List<ColumnMetadata>> columnsByTable = columns.stream()
                .collect(Collectors.groupingBy(
                        ColumnMetadata::tableName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return columnsByTable.entrySet().stream()
                .filter(entry -> isTableVisible(entry.getKey(), allow, deny))
                .map(entry -> {
                    String table = entry.getKey();
                    List<ColumnDto> cols = entry.getValue().stream()
                            .map(c -> new ColumnDto(
                                    c.columnName(),
                                    c.dataType(),
                                    pkKeys.contains(table + "." + c.columnName()),
                                    sensitiveColumns.contains(table + "." + c.columnName())
                            ))
                            .toList();
                    return new TableDto(table, cols);
                })
                .toList();
    }

    /**
     * Возвращает имя primary key колонки для конкретной таблицы.
     *
     * @param serviceKey ключ сервиса (например, "auth")
     * @param tableName  имя таблицы (например, "users")
     * @return имя PK колонки
     */
    public Mono<String> getPrimaryKeyColumn(String serviceKey, String tableName) {
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            log.warn("Service not found: {}", serviceKey);
            return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                    "Service not found: " + serviceKey));
        }

        String schema = serviceProps.schema();

        return schemaRepository.findPrimaryKeys(serviceKey, schema)
                .filter(pk -> pk.tableName().equals(tableName))
                .map(PrimaryKeyMetadata::columnName)
                .next()
                .switchIfEmpty(Mono.defer(() -> resolveMissingPrimaryKey(serviceKey, schema, tableName)));
    }

    /**
     * Если PK не найден: либо таблицы нет в схеме, либо у неё нет primary key.
     * Различаем эти случаи, чтобы клиент получал осмысленную ошибку.
     */
    private Mono<String> resolveMissingPrimaryKey(String serviceKey, String schema, String tableName) {
        return tableExists(serviceKey, schema, tableName)
                .flatMap(exists -> {
                    if (!exists) {
                        log.warn("Table not found: '{}' in service '{}'", tableName, serviceKey);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                                "Table not found: " + tableName));
                    }
                    log.warn("No primary key found for table '{}' in service '{}'", tableName, serviceKey);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                            "No primary key found for table: " + tableName));
                });
    }

    private Mono<Boolean> tableExists(String serviceKey, String schema, String tableName) {
        return schemaRepository.findColumns(serviceKey, schema)
                .filter(column -> column.tableName().equals(tableName))
                .hasElements();
    }

    /**
     * Возвращает колонки таблицы с их типами.
     *
     * @param serviceKey ключ сервиса (например, "auth")
     * @param tableName  имя таблицы (например, "users")
     * @return map: имя колонки → тип
     */
    public Mono<Map<String, DbColumnType>> getTableColumns(String serviceKey, String tableName) {
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Service not found: " + serviceKey));
        }

        String schema = serviceProps.schema();

        return schemaRepository.findColumns(serviceKey, schema)
                .filter(column -> column.tableName().equals(tableName))
                .collectList()
                .map(columns -> columns.stream()
                        .collect(Collectors.toMap(
                                ColumnMetadata::columnName,
                                c -> DbColumnType.fromPgType(c.dataType()),
                                (a, b) -> a,
                                LinkedHashMap::new
                        )));
    }

    /**
     * Пустой allowlist означает «разрешены все таблицы».
     * Denylist имеет приоритет над allowlist.
     */
    static boolean isTableVisible(String table, Set<String> allow, Set<String> deny) {
        if (deny.contains(table)) {
            return false;
        }
        return allow.isEmpty() || allow.contains(table);
    }
}
