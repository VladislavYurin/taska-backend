package ru.taska.repository.executor;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.builder.SearchQuery;

/**
 * Исполнитель поисковых запросов.
 * Отвечает за выполнение SQL-запросов с подстановкой параметров.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchQueryExecutor {

    private final DatabaseClient databaseClient;
    private final IssueMapper issueMapper;

    /**
     * Выполняет поисковый запрос и возвращает список задач.
     *
     * @param query готовый запрос с SQL и параметрами
     * @return Flux с задачами
     */
    public Flux<Issue> executeQuery(SearchQuery query) {
        log.debug("Executing search query: {}", query.getSql());
        log.trace("Query parameters: {}", query.getParams());

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(query.getSql());
        spec = bindParams(spec, query);

        return spec.map(issueMapper::mapRowToIssue)
                .all()
                .doOnComplete(() -> log.debug("Search query completed successfully"))
                .doOnError(error -> log.error("Error executing search query: {}", error.getMessage(), error));
    }

    /**
     * Выполняет запрос подсчета количества записей.
     *
     * @param query готовый запрос с SQL и параметрами
     * @return Mono с количеством записей
     */
    public Mono<Long> executeCount(SearchQuery query) {
        log.debug("Executing count query: {}", query.getSql());
        log.trace("Query parameters: {}", query.getParams());

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(query.getSql());
        spec = bindParams(spec, query);

        return spec.map(row -> {
                    Number count = row.get("count", Number.class);
                    return count != null ? count.longValue() : 0L;
                })
                .one()
                .doOnSuccess(count -> log.debug("Count query completed: {} records", count))
                .doOnError(error -> log.error("Error executing count query: {}", error.getMessage(), error));
    }

    /**
     * Выполняет поисковый запрос с кастомным маппером.
     *
     * @param query  готовый запрос с SQL и параметрами
     * @param mapper функция для маппинга строки в объект
     * @param <T>    тип возвращаемого объекта
     * @return Flux с объектами типа T
     */
    public <T> Flux<T> executeQuery(SearchQuery query, java.util.function.BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, T> mapper) {
        log.debug("Executing custom search query: {}", query.getSql());
        log.trace("Query parameters: {}", query.getParams());

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(query.getSql());
        spec = bindParams(spec, query);

        return spec.map(mapper)
                .all()
                .doOnComplete(() -> log.debug("Custom search query completed successfully"))
                .doOnError(error -> log.error("Error executing custom search query: {}", error.getMessage(), error));
    }

    /**
     * Биндит параметры в SQL-запрос.
     *
     * @param spec  спецификация запроса
     * @param query запрос с параметрами
     * @return спецификация запроса с привязанными параметрами
     */
    private DatabaseClient.GenericExecuteSpec bindParams(
            DatabaseClient.GenericExecuteSpec spec,
            SearchQuery query
    ) {
        for (var entry : query.getParams().entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            if (paramValue == null) {
                log.trace("Skipping null parameter: {}", paramName);
                continue;
            }

            log.trace("Binding parameter: {} = {}", paramName, paramValue);
            spec = spec.bind(paramName, paramValue);
        }

        return spec;
    }

    /**
     * Выполняет поисковый запрос с дополнительной обработкой ошибок.
     *
     * @param query          готовый запрос с SQL и параметрами
     * @param errorMessage   сообщение об ошибке
     * @return Flux с задачами
     */
    public Flux<Issue> executeQueryWithErrorHandling(SearchQuery query, String errorMessage) {
        return executeQuery(query)
                .onErrorResume(error -> {
                    log.error("{}: {}", errorMessage, error.getMessage(), error);
                    return Flux.error(error);
                });
    }

    /**
     * Выполняет запрос подсчета с дополнительной обработкой ошибок.
     *
     * @param query          готовый запрос с SQL и параметрами
     * @param errorMessage   сообщение об ошибке
     * @return Mono с количеством записей
     */
    public Mono<Long> executeCountWithErrorHandling(SearchQuery query, String errorMessage) {
        return executeCount(query)
                .onErrorResume(error -> {
                    log.error("{}: {}", errorMessage, error.getMessage(), error);
                    return Mono.error(error);
                });
    }
}