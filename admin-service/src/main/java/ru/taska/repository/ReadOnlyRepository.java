package ru.taska.repository;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import java.util.List;
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
     * @param sql        - SQL запрос с плейсхолдерами
     * @param params     - значения для плейсхолдеров
     * @return Flux<Map<String, Object>> - строки таблицы
     */
    public Flux<Map<String,Object>> executeQuery(
            String serviceKey,
            String sql,
            List<Object> params
    ){
        return Flux.defer(()->{
            DatabaseClient client = getClient(serviceKey);
            return client.sql(sql)
                    .bindValues(params)
                    .fetch()
                    .all();
        });
    }

    /**
     * Выполняет COUNT запрос для подсчета общего количества записей.
     *
     * @param serviceKey - ключ сервиса ("user-service") - для выбора БД
     * @param sql        - COUNT SQL запрос с плейсхолдерами
     * @param params     - значения для плейсхолдеров
     * @return Mono<Long> - количество записей
     */
    public Mono<Long> countRows(
            String serviceKey,
            String sql,
            List<Object> params
    ) {
        return Mono.defer(()->{
            DatabaseClient client = getClient(serviceKey);
            return client.sql(sql)
                    .bindValues(params)
                    .map(row -> row.get(0, Long.class))
                    .first();
        });
    }
}
