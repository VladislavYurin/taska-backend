package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;
import ru.taska.dto.ProblematicEventCountDto;
import ru.taska.dto.ProblematicOutboxEventResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.ProblematicOutboxEventService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Интеграционные тесты для ProblematicOutboxEventServiceImpl.
 * Проверяют корректность SQL-запросов, маскирование данных и агрегацию результатов
 * через реальный PostgreSQL (Testcontainers).
 */
class ProblematicOutboxEventIT extends AbstractIT {

    private static final String SERVICE_AUTH = "auth";
    private static final String SERVICE_ISSUE = "issue";
    private static final String REQUEST_ID = "it-request-id";
    private static final String NODE_ID = "it-node-id";

    @Autowired
    private ProblematicOutboxEventService problematicOutboxEventService;

    @BeforeEach
    void prepareOutboxTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + FIXTURE_SCHEMA + ".outbox_events ("
                    + "id uuid PRIMARY KEY DEFAULT gen_random_uuid(), "
                    + "aggregate_type text NOT NULL, "
                    + "aggregate_id uuid NOT NULL, "
                    + "event_type text NOT NULL, "
                    + "payload jsonb NOT NULL, "
                    + "status text NOT NULL DEFAULT 'NEW', "
                    + "created_at timestamptz NOT NULL DEFAULT now(), "
                    + "published_at timestamptz, "
                    + "attempts integer NOT NULL DEFAULT 0, "
                    + "last_error_message text, "
                    + "processing_started_at timestamptz, "
                    + "request_id text"
                    + ")");
            stmt.execute("DELETE FROM " + FIXTURE_SCHEMA + ".outbox_events");
        }
    }

    @Test
    void shouldCountFailedStuckAndOverdueEvents() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime longAgo = now.minusHours(1);

        insertEvent(SERVICE_AUTH, "FAILED", longAgo, null);
        insertEvent(SERVICE_AUTH, "FAILED", longAgo, null);
        insertEvent(SERVICE_AUTH, "PROCESSING", longAgo, longAgo);
        insertEvent(SERVICE_AUTH, "NEW", longAgo, null);
        insertEvent(SERVICE_AUTH, "NEW", longAgo, null);
        insertEvent(SERVICE_AUTH, "NEW", longAgo, null);
        // Нормальные события — не должны попасть в счётчики
        insertEvent(SERVICE_AUTH, "PUBLISHED", longAgo, null);
        insertEvent(SERVICE_AUTH, "NEW", now, null); // свежий NEW — не overdue

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    ProblematicEventCountDto authCount = findCount(response, SERVICE_AUTH);
                    Assertions.assertThat(authCount.failedCount()).isEqualTo(2);
                    Assertions.assertThat(authCount.stuckProcessingCount()).isEqualTo(1);
                    Assertions.assertThat(authCount.overdueNewCount()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotCountFreshProcessingAsStuck() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // PROCESSING начат только что — не застрял
        insertEvent(SERVICE_AUTH, "PROCESSING", now.minusHours(1), now);

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    ProblematicEventCountDto authCount = findCount(response, SERVICE_AUTH);
                    Assertions.assertThat(authCount.stuckProcessingCount()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyWhenNoProblematicEvents() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertEvent(SERVICE_AUTH, "PUBLISHED", now.minusHours(1), null);
        insertEvent(SERVICE_AUTH, "NEW", now, null); // свежий — не overdue

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    Assertions.assertThat(response.events()).isEmpty();
                    ProblematicEventCountDto authCount = findCount(response, SERVICE_AUTH);
                    Assertions.assertThat(authCount.failedCount()).isZero();
                    Assertions.assertThat(authCount.stuckProcessingCount()).isZero();
                    Assertions.assertThat(authCount.overdueNewCount()).isZero();
                    Assertions.assertThat(response.notAllShown()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void shouldFetchEventsOrderedByCreatedAt() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime oldest = now.minusHours(3);
        OffsetDateTime middle = now.minusHours(2);
        OffsetDateTime newest = now.minusHours(1);

        insertEvent(SERVICE_AUTH, "FAILED", newest, null);
        insertEvent(SERVICE_AUTH, "FAILED", oldest, null);
        insertEvent(SERVICE_AUTH, "FAILED", middle, null);

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    Assertions.assertThat(response.events()).hasSize(3);
                    Assertions.assertThat(response.events().get(0).createdAt())
                            .isBefore(response.events().get(1).createdAt());
                    Assertions.assertThat(response.events().get(1).createdAt())
                            .isBefore(response.events().get(2).createdAt());
                })
                .verifyComplete();
    }

    @Test
    void shouldAssignCorrectReasonPerStatus() throws Exception {
        OffsetDateTime longAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        insertEvent(SERVICE_AUTH, "FAILED", longAgo, null);
        insertEvent(SERVICE_AUTH, "PROCESSING", longAgo, longAgo);
        insertEvent(SERVICE_AUTH, "NEW", longAgo, null);

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    for (ProblematicOutboxEventResponseDto event : response.events()) {
                        switch (event.status()) {
                            case "FAILED" -> Assertions.assertThat(event.reason())
                                    .isEqualTo("Event processing failed");
                            case "PROCESSING" -> Assertions.assertThat(event.reason())
                                    .contains("PROCESSING");
                            case "NEW" -> Assertions.assertThat(event.reason())
                                    .contains("NEW");
                            default -> Assertions.fail("Unexpected status: " + event.status());
                        }
                    }
                })
                .verifyComplete();
    }

    @Test
    void shouldMaskSensitiveJsonFieldsInPayload() throws Exception {
        OffsetDateTime longAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        // auth-сервис имеет sensitive-json-fields: outbox_events.payload.email -> MASK_PARTIAL
        insertEventWithPayload(SERVICE_AUTH, "FAILED", longAgo,
                "{\"email\": \"alice@example.com\", \"name\": \"Alice\"}");

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    Assertions.assertThat(response.events()).hasSize(1);
                    String payload = response.events().get(0).payload();
                    // email должен быть замаскирован (MASK_PARTIAL), name — нет
                    Assertions.assertThat(payload).doesNotContain("alice@example.com");
                    Assertions.assertThat(payload).contains("Alice");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnCountsForAllServicesWhenServiceKeyIsNull() throws Exception {
        OffsetDateTime longAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        insertEvent(SERVICE_AUTH, "FAILED", longAgo, null);
        insertEvent(SERVICE_ISSUE, "FAILED", longAgo, null);

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        null, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    // Счётчики должны быть для всех сервисов из конфигурации (auth, issue, project)
                    Assertions.assertThat(response.counts()).hasSize(3);
                    Assertions.assertThat(response.counts())
                            .extracting(ProblematicEventCountDto::serviceKey)
                            .contains(SERVICE_AUTH, SERVICE_ISSUE, "project");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEventsOnlyForSpecifiedService() throws Exception {
        OffsetDateTime longAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        insertEvent(SERVICE_AUTH, "FAILED", longAgo, null);
        insertEvent(SERVICE_ISSUE, "FAILED", longAgo, null);

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    // События только для auth
                    Assertions.assertThat(response.events())
                            .allSatisfy(e -> Assertions.assertThat(e.serviceKey()).isEqualTo(SERVICE_AUTH));
                    // Но счётчики для всех сервисов
                    Assertions.assertThat(response.counts()).hasSize(3);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectUnknownServiceKey() {
        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        "unknown-service", REQUEST_ID, NODE_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException de = (DomainException) error;
                    Assertions.assertThat(de.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(de.getMessage()).contains("unknown-service");
                })
                .verify();
    }

    @Test
    void shouldMapAllFieldsFromDatabaseRow() throws Exception {
        OffsetDateTime longAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        String eventId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String aggregateId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + FIXTURE_SCHEMA + ".outbox_events "
                    + "(id, aggregate_type, aggregate_id, event_type, payload, status, "
                    + "created_at, attempts, last_error_message, request_id) VALUES ("
                    + "'" + eventId + "', "
                    + "'Order', "
                    + "'" + aggregateId + "', "
                    + "'OrderCreated', "
                    + "'{\"name\": \"test\"}'::jsonb, "
                    + "'FAILED', "
                    + "'" + longAgo + "', "
                    + "3, "
                    + "'Connection refused', "
                    + "'req-123'"
                    + ")");
        }

        StepVerifier.create(problematicOutboxEventService.getProblematicOutboxEventsSummary(
                        SERVICE_AUTH, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    Assertions.assertThat(response.events()).hasSize(1);
                    ProblematicOutboxEventResponseDto event = response.events().get(0);
                    Assertions.assertThat(event.id()).isEqualTo(eventId);
                    Assertions.assertThat(event.aggregateType()).isEqualTo("Order");
                    Assertions.assertThat(event.aggregateId()).isEqualTo(aggregateId);
                    Assertions.assertThat(event.eventType()).isEqualTo("OrderCreated");
                    Assertions.assertThat(event.status()).isEqualTo("FAILED");
                    Assertions.assertThat(event.attempts()).isEqualTo(3);
                    Assertions.assertThat(event.lastErrorMessage()).isEqualTo("Connection refused");
                    Assertions.assertThat(event.requestId()).isEqualTo("req-123");
                    Assertions.assertThat(event.serviceKey()).isEqualTo(SERVICE_AUTH);
                    Assertions.assertThat(event.createdAt()).isNotNull();
                    Assertions.assertThat(event.reason()).isEqualTo("Event processing failed");
                })
                .verifyComplete();
    }

    private ProblematicEventCountDto findCount(GetProblematicOutboxEventsSummaryResponseDto response,
                                               String serviceKey) {
        Optional<ProblematicEventCountDto> count = response.counts().stream()
                .filter(c -> serviceKey.equals(c.serviceKey()))
                .findFirst();
        Assertions.assertThat(count).as("Count for service " + serviceKey).isPresent();
        return count.get();
    }

    private void insertEvent(String serviceKey, String status,
                             OffsetDateTime createdAt, OffsetDateTime processingStartedAt) throws Exception {
        insertEventWithPayload(serviceKey, status, createdAt, processingStartedAt, "{\"type\": \"test\"}");
    }

    private void insertEventWithPayload(String serviceKey, String status,
                                        OffsetDateTime createdAt, String payload) throws Exception {
        insertEventWithPayload(serviceKey, status, createdAt, null, payload);
    }

    private void insertEventWithPayload(String serviceKey, String status,
                                        OffsetDateTime createdAt, OffsetDateTime processingStartedAt,
                                        String payload) throws Exception {
        // Все readonly-подключения указывают на одну и ту же БД/схему в тестах
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = connection.createStatement()) {
            String processingStartedAtSql = processingStartedAt != null
                    ? "'" + processingStartedAt + "'"
                    : "NULL";
            stmt.execute("INSERT INTO " + FIXTURE_SCHEMA + ".outbox_events "
                    + "(aggregate_type, aggregate_id, event_type, payload, status, "
                    + "created_at, processing_started_at, attempts) VALUES ("
                    + "'TestAggregate', "
                    + "'" + java.util.UUID.randomUUID() + "', "
                    + "'TestEvent', "
                    + "'" + payload + "'::jsonb, "
                    + "'" + status + "', "
                    + "'" + createdAt + "', "
                    + processingStartedAtSql + ", "
                    + "0"
                    + ")");
        }
    }
}
