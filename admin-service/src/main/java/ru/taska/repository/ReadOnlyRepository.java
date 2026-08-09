package ru.taska.repository;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
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

    /**
     * Получение DatabaseClient для выполнения запросов
     * @param serviceKey - переданное в запросе название сервиса, к БД которого будем подключаться
     * @return DatabaseClient
     */
    private DatabaseClient getClient(String serviceKey) {
        DatabaseClient client = readonlyDatabaseClient.get(serviceKey);
        if (client == null) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,"No database client for service: " + serviceKey);
        }
        return client;
    }

    /**
     * Выполняет SELECT запрос и возвращает строки
     *
     * @param serviceKey - нужен, чтобы выбрать DatabaseClient для конкретной БД
     * @param sqlQuery   - содержит SQL с плейсхолдерами и параметры
     * @return Flux<Map<String, Object>> - строки таблицы
     */
    public Flux<Map<String,Object>> executeQuery(
            String serviceKey,
            ReadOnlyQueryBuilder.SqlQuery sqlQuery
    ){
        // Получаем DatabaseClient по сервису(serviceKey)
        return Flux.defer(()->{
            DatabaseClient client = getClient(serviceKey);
            // Выполняем запрос
            //    sqlQuery.sql() = "SELECT * FROM users WHERE status = $1 AND email = $2"
            //    sqlQuery.params() = ["active", "john@gmail.com"]
            return client.sql(sqlQuery.sql())
                    .bindValues(sqlQuery.params())
                    .fetch()
                    .all();
        });
    }

    /**
     * Выполняет COUNT запрос для подсчета общего количества записей.
     *
     * @param serviceKey ключ сервиса ("user-service") - для выбора БД
     * @param safeCountQuery   COUNT SQL с плейсхолдерами и параметрами
     * @return Mono<Long> - количество записей
     */
    public Mono<Long> countRows(
            String serviceKey,
            ReadOnlyQueryBuilder.SqlQuery safeCountQuery
    ) {
        // Получаем DatabaseClient по сервису(serviceKey)
        return Mono.defer(()->{
            DatabaseClient client = getClient(serviceKey);
            // Выполняем запрос
            //    safeCountQuery.sql() = "SELECT COUNT FROM users WHERE status = $1 AND email = $2"
            //    safeCountQuery.params() = ["active", "john@gmail.com"]
            return client.sql(safeCountQuery.sql())
                    .bindValues(safeCountQuery.params())
                    .map(row -> row.get(0, Long.class))
                    .first();
        });
    }
}
