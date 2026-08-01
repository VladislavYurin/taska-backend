package ru.taska.repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.service.ReadOnlyQueryBuilder;

import java.util.Map;

/**
 * Выполняет SQL запросы к БД через готовые DatabaseClient
 */
@Repository
@Slf4j
public class ReadOnlyRepository {

    @Resource(name = "readonlyDatabaseClients")
    private Map<String, DatabaseClient> readonlyDatabaseClient;

    @PostConstruct
    void init() {
        log.info("ReadOnlyRepository initialized with clients: {}", readonlyDatabaseClient.keySet());
    }

    /**
     * Выполняет SELECT запрос и возвращает строки
     *
     * @param serviceKey - нужен, чтобы выбрать DatabaseClient для конкретной БД
     * @param sqlQuery   - содержит SQL с плейсхолдерами и параметры
     * @return Flux<Map<String, Object>> - строки таблицы
     */
    public Flux<Map<String,Object>> executeQuery(String serviceKey, ReadOnlyQueryBuilder.SqlQuery sqlQuery){
        // 1. Выбираем DatabaseClient
        DatabaseClient client = readonlyDatabaseClient.get(serviceKey);
        if (client == null) {
            return Flux.error(
                    new IllegalArgumentException("No database client for service: " + serviceKey)
            );
        }

        // 2. Выполняем запрос
        //    sqlQuery.sql() = "SELECT * FROM users WHERE status = $1 AND email = $2"
        //    sqlQuery.params() = ["active", "john@gmail.com"]
        return client.sql(sqlQuery.sql())
                .bindValues(sqlQuery.params())
                .fetch()
                .all();
    }

    /**
     * Выполняет COUNT запрос для подсчета общего количества записей.
     *
     * @param serviceKey ключ сервиса ("user-service") - для выбора БД
     * @param sqlSafeCountQuery   COUNT SQL с плейсхолдерами и параметрами
     * @return Mono<Long> - количество записей
     */
    public Mono<Long> countRows(
            String serviceKey,
            ReadOnlyQueryBuilder.SqlQuery sqlSafeCountQuery
    ) {
        // 1. Выбираем DatabaseClient
        DatabaseClient client = readonlyDatabaseClient.get(serviceKey);
        if (client == null) {
            return Mono.error(new IllegalArgumentException("No database client for service: " + serviceKey));
        }

        // 2. Выполняем COUNT запрос
        //    sqlQuery.sql() = "SELECT COUNT(*) FROM users WHERE status = $1"
        //    sqlQuery.params() = ["active"]
        return client.sql(sqlSafeCountQuery.sql())
                .bindValues(sqlSafeCountQuery.params()) // Подставляет значения в плейсхолдеры
                .map(row -> row.get(0, Long.class))
                .first();
    }
}
