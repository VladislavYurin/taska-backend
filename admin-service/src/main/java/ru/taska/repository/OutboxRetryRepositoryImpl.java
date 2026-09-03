package ru.taska.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Реализация ограниченного административного доступа
 * к outbox-таблицам сервисов.
 * <p>
 * Репозиторий предоставляет только операции, необходимые
 * для ручного retry outbox-события. Generic write-доступ
 * к таблицам сервисных БД отсутствует.
 */
@Repository
public class OutboxRetryRepositoryImpl implements OutboxRetryRepository {

    private static final String FIND_BY_ID_SQL = """
            SELECT id,
                   aggregate_type,
                   aggregate_id,
                   event_type,
                   status,
                   payload::text AS payload,
                   attempts,
                   last_error_message,
                   created_at,
                   published_at,
                   processing_started_at,
                   request_id
            FROM taska.outbox_events
            WHERE id = :eventId
            """;

    private static final String RETRY_SQL = """
            UPDATE taska.outbox_events
            SET status = 'NEW',
                last_error_message = NULL,
                processing_started_at = NULL
            WHERE id = :eventId
              AND (
                    status = 'FAILED'
                    OR (
                        status = 'PROCESSING'
                        AND processing_started_at < :stuckBefore
                    )
                  )
            """;

    private final Map<String, DatabaseClient> outboxWriteDatabaseClients;
    private final ObjectMapper objectMapper;

    /**
     * Создаёт repository для работы с разрешёнными outbox datasource.
     *
     * @param outboxWriteDatabaseClients write-клиенты сервисных БД
     * @param objectMapper                mapper для десериализации payload
     */
    public OutboxRetryRepositoryImpl(
            @Qualifier("outboxWriteDatabaseClients")
            Map<String, DatabaseClient> outboxWriteDatabaseClients,
            ObjectMapper objectMapper
    ) {
        this.outboxWriteDatabaseClients = outboxWriteDatabaseClients;
        this.objectMapper = objectMapper;
    }

    /**
     * Получает текущее состояние outbox-события.
     *
     * @param service сервис-владелец outbox
     * @param eventId идентификатор события
     * @return snapshot события или empty, если оно не найдено
     */
    @Override
    public Mono<OutboxEventSnapshot> findById(
            String service,
            UUID eventId
    ) {
        DatabaseClient databaseClient = getDatabaseClient(service);

        return databaseClient.sql(FIND_BY_ID_SQL)
                .bind("eventId", eventId)
                .map((row, metadata) -> new OutboxEventSnapshot(
                        row.get("id", UUID.class),
                        row.get("aggregate_type", String.class),
                        row.get("aggregate_id", UUID.class),
                        row.get("event_type", String.class),
                        parseStatus(row.get("status", String.class)),
                        parsePayload(row.get("payload", String.class)),
                        row.get("attempts", Integer.class),
                        row.get("last_error_message", String.class),
                        row.get("created_at", Instant.class),
                        row.get("published_at", Instant.class),
                        row.get("processing_started_at", Instant.class),
                        row.get("request_id", String.class)
                ))
                .one();
    }

    /**
     * Атомарно переводит допустимое outbox-событие обратно в NEW.
     * <p>
     * UPDATE выполняется только для FAILED либо зависшего PROCESSING.
     * NEW и PUBLISHED не удовлетворяют условию WHERE.
     * <p>
     * Payload и attempts не входят в UPDATE и поэтому сохраняются без изменений.
     *
     * @param service     сервис-владелец outbox
     * @param eventId     идентификатор события
     * @param stuckBefore граница определения зависшего PROCESSING
     * @return количество изменённых строк
     */
    @Override
    public Mono<Long> retry(
            String service,
            UUID eventId,
            Instant stuckBefore
    ) {
        DatabaseClient databaseClient = getDatabaseClient(service);

        return databaseClient.sql(RETRY_SQL)
                .bind("eventId", eventId)
                .bind("stuckBefore", stuckBefore)
                .fetch()
                .rowsUpdated();
    }

    /**
     * Возвращает DatabaseClient только для явно поддерживаемого сервиса.
     *
     * @param service имя сервиса
     * @return write DatabaseClient
     * @throws DomainException если сервис не поддерживает outbox retry
     */
    private DatabaseClient getDatabaseClient(String service) {
        String normalizedService =
                service.trim().toLowerCase(Locale.ROOT);

        DatabaseClient databaseClient =
                outboxWriteDatabaseClients.get(normalizedService);

        if (databaseClient == null) {
            throw new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Unsupported outbox service: " + service
            );
        }

        return databaseClient;
    }

    /**
     * Преобразует строковый статус из БД в доменный enum.
     *
     * @param status статус из outbox_events
     * @return доменный статус
     */
    private OutboxStatus parseStatus(String status) {
        if (status == null) {
            throw new IllegalStateException(
                    "Outbox event status must not be null"
            );
        }

        try {
            return OutboxStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Unsupported outbox event status: " + status,
                    ex
            );
        }
    }

    /**
     * Преобразует JSON payload из БД в JsonNode.
     *
     * @param payload JSON в текстовом представлении
     * @return payload в виде JsonNode
     */
    private JsonNode parsePayload(String payload) {
        if (payload == null) {
            return null;
        }

        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize outbox payload",
                    ex
            );
        }
    }
}