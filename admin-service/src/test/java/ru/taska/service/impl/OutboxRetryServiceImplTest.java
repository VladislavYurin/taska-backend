package ru.taska.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.OutboxRetryProperties;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.dto.AuditEventDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxRetryRepository;
import ru.taska.service.AuditService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты административного retry outbox-событий.
 * <p>
 * Проверяют разрешённые и запрещённые состояния,
 * неизменность payload и обязательную запись аудита.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetryServiceImplTest {

    private static final String REQUEST_ID = "request-123";
    private static final String SERVICE = "issue";
    private static final String REASON = "Manual retry after investigation";
    private static final String ACTOR_LOGIN = "global-admin";

    private static final UUID EVENT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID AGGREGATE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ACTOR_USER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String NODE_ID = "admin-test-node";

    @Mock
    private OutboxRetryRepository outboxRetryRepository;

    @Mock
    private AuditService auditService;

    private ObjectMapper objectMapper;
    private JsonNode payload;
    private JsonNode actorRoles;

    private OutboxRetryServiceImpl service;

    /**
     * Подготавливает зависимости сервиса перед каждым тестом.
     *
     * @throws Exception если тестовый JSON не удалось создать
     */
    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();

        payload = objectMapper.readTree("""
                {
                  "issueId": "22222222-2222-2222-2222-222222222222",
                  "event": "ISSUE_UPDATED"
                }
                """);

        actorRoles = objectMapper.valueToTree(
                List.of("GLOBAL_ADMIN")
        );

        OutboxRetryProperties properties =
                new OutboxRetryProperties(
                        Duration.ofMinutes(10)
                );

        service = new OutboxRetryServiceImpl(
                outboxRetryRepository,
                auditService,
                properties,
                objectMapper
        );
    }

    /**
     * Проверяет успешный retry FAILED-события.
     */
    @Test
    @DisplayName("FAILED событие переводится в NEW и записывается в audit")
    void retryFailedEvent_shouldRetryAndWriteAudit() {
        OutboxEventSnapshot oldSnapshot = snapshot(
                OutboxStatus.FAILED,
                3,
                "Kafka unavailable",
                null,
                payload
        );

        OutboxEventSnapshot newSnapshot = snapshot(
                OutboxStatus.NEW,
                3,
                null,
                null,
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(oldSnapshot))
                .thenReturn(Mono.just(newSnapshot));

        when(outboxRetryRepository.retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        )).thenReturn(Mono.just(1L));

        when(auditService.logAudit(any(AuditEventDto.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .expectNext(newSnapshot)
                .verifyComplete();

        ArgumentCaptor<AuditEventDto> auditCaptor =
                ArgumentCaptor.forClass(AuditEventDto.class);

        verify(auditService).logAudit(auditCaptor.capture());

        AuditEventDto audit = auditCaptor.getValue();

        assertEquals(REQUEST_ID, audit.getRequestId());
        assertEquals(ACTOR_USER_ID, audit.getActorUserId());
        assertEquals(ACTOR_LOGIN, audit.getActorLogin());
        assertEquals(actorRoles, audit.getActorRoles());

        assertEquals("RETRY_OUTBOX_EVENT", audit.getAction());
        assertEquals(SERVICE, audit.getTargetService());
        assertEquals("outbox_events", audit.getTargetTable());
        assertEquals(EVENT_ID.toString(), audit.getTargetId());
        assertEquals(REASON, audit.getReason());

        assertEquals(
                objectMapper.valueToTree(oldSnapshot),
                audit.getOldValue()
        );

        assertEquals(
                objectMapper.valueToTree(newSnapshot),
                audit.getNewValue()
        );
    }

    /**
     * Проверяет успешный retry зависшего PROCESSING-события.
     */
    @Test
    @DisplayName("Зависшее PROCESSING событие можно перевести в NEW")
    void retryStuckProcessingEvent_shouldRetry() {
        OutboxEventSnapshot oldSnapshot = snapshot(
                OutboxStatus.PROCESSING,
                2,
                null,
                Instant.now().minus(Duration.ofMinutes(20)),
                payload
        );

        OutboxEventSnapshot newSnapshot = snapshot(
                OutboxStatus.NEW,
                2,
                null,
                null,
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(oldSnapshot))
                .thenReturn(Mono.just(newSnapshot));

        when(outboxRetryRepository.retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        )).thenReturn(Mono.just(1L));

        when(auditService.logAudit(any(AuditEventDto.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .expectNext(newSnapshot)
                .verifyComplete();

        verify(outboxRetryRepository).retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        );

        verify(auditService)
                .logAudit(any(AuditEventDto.class));
    }

    /**
     * Проверяет запрет retry для NEW.
     */
    @Test
    @DisplayName("NEW событие нельзя retry")
    void retryNewEvent_shouldFail() {
        OutboxEventSnapshot snapshot = snapshot(
                OutboxStatus.NEW,
                0,
                null,
                null,
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(snapshot));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.FAILED_PRECONDITION,
                            exception.getStatus()
                    );
                });

        verify(outboxRetryRepository, never())
                .retry(
                        any(),
                        any(),
                        any()
                );

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет запрет retry для PUBLISHED.
     */
    @Test
    @DisplayName("PUBLISHED событие нельзя retry")
    void retryPublishedEvent_shouldFail() {
        OutboxEventSnapshot snapshot = snapshot(
                OutboxStatus.PUBLISHED,
                1,
                null,
                null,
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(snapshot));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.FAILED_PRECONDITION,
                            exception.getStatus()
                    );
                });

        verify(outboxRetryRepository, never())
                .retry(
                        any(),
                        any(),
                        any()
                );

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет запрет retry для ещё не зависшего PROCESSING.
     */
    @Test
    @DisplayName("Свежий PROCESSING нельзя retry")
    void retryFreshProcessingEvent_shouldFail() {
        OutboxEventSnapshot snapshot = snapshot(
                OutboxStatus.PROCESSING,
                1,
                null,
                Instant.now().minus(Duration.ofMinutes(1)),
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(snapshot));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.FAILED_PRECONDITION,
                            exception.getStatus()
                    );
                });

        verify(outboxRetryRepository, never())
                .retry(
                        any(),
                        any(),
                        any()
                );

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет обработку отсутствующего события.
     */
    @Test
    @DisplayName("Неизвестный eventId возвращает NOT_FOUND")
    void retryMissingEvent_shouldFail() {
        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.NOT_FOUND,
                            exception.getStatus()
                    );
                });

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет защиту от конкурентного изменения события.
     */
    @Test
    @DisplayName("Если guarded UPDATE изменил 0 строк, retry завершается ошибкой")
    void retryConcurrentChange_shouldFail() {
        OutboxEventSnapshot oldSnapshot = snapshot(
                OutboxStatus.FAILED,
                3,
                "Kafka unavailable",
                null,
                payload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(oldSnapshot));

        when(outboxRetryRepository.retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        )).thenReturn(Mono.just(0L));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.FAILED_PRECONDITION,
                            exception.getStatus()
                    );
                });

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет защиту от изменения payload во время retry.
     *
     * @throws Exception если тестовый JSON не удалось создать
     */
    @Test
    @DisplayName("Изменение payload во время retry приводит к INTERNAL")
    void retryPayloadChanged_shouldFail() throws Exception {
        OutboxEventSnapshot oldSnapshot = snapshot(
                OutboxStatus.FAILED,
                3,
                "Kafka unavailable",
                null,
                payload
        );

        JsonNode changedPayload = objectMapper.readTree("""
                {
                  "unexpected": "changed"
                }
                """);

        OutboxEventSnapshot newSnapshot = snapshot(
                OutboxStatus.NEW,
                3,
                null,
                null,
                changedPayload
        );

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(oldSnapshot))
                .thenReturn(Mono.just(newSnapshot));

        when(outboxRetryRepository.retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        )).thenReturn(Mono.just(1L));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable -> {
                    DomainException exception =
                            assertInstanceOf(
                                    DomainException.class,
                                    throwable
                            );

                    assertEquals(
                            DomainStatus.INTERNAL,
                            exception.getStatus()
                    );
                });

        verifyNoInteractions(auditService);
    }

    /**
     * Проверяет, что ошибка сохранения аудита делает операцию неуспешной.
     */
    @Test
    @DisplayName("Ошибка audit должна передаваться вызывающему коду")
    void retryAuditFailure_shouldPropagateError() {
        OutboxEventSnapshot oldSnapshot = snapshot(
                OutboxStatus.FAILED,
                3,
                "Kafka unavailable",
                null,
                payload
        );

        OutboxEventSnapshot newSnapshot = snapshot(
                OutboxStatus.NEW,
                3,
                null,
                null,
                payload
        );

        RuntimeException auditException =
                new RuntimeException("Audit database unavailable");

        when(outboxRetryRepository.findById(SERVICE, EVENT_ID))
                .thenReturn(Mono.just(oldSnapshot))
                .thenReturn(Mono.just(newSnapshot));

        when(outboxRetryRepository.retry(
                eq(SERVICE),
                eq(EVENT_ID),
                any(Instant.class)
        )).thenReturn(Mono.just(1L));

        when(auditService.logAudit(any(AuditEventDto.class)))
                .thenReturn(Mono.error(auditException));

        StepVerifier.create(service.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        EVENT_ID,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .verifyErrorSatisfies(throwable ->
                        assertSame(auditException, throwable)
                );
    }

    /**
     * Создаёт тестовый snapshot outbox-события.
     *
     * @param status              статус события
     * @param attempts            число попыток
     * @param lastErrorMessage    последняя ошибка
     * @param processingStartedAt время начала обработки
     * @param eventPayload        payload события
     * @return тестовый snapshot
     */
    private OutboxEventSnapshot snapshot(
            OutboxStatus status,
            Integer attempts,
            String lastErrorMessage,
            Instant processingStartedAt,
            JsonNode eventPayload
    ) {
        return new OutboxEventSnapshot(
                EVENT_ID,
                "ISSUE",
                AGGREGATE_ID,
                "ISSUE_UPDATED",
                status,
                eventPayload,
                attempts,
                lastErrorMessage,
                Instant.now().minus(Duration.ofHours(1)),
                status == OutboxStatus.PUBLISHED
                        ? Instant.now().minus(Duration.ofMinutes(5))
                        : null,
                processingStartedAt,
                REQUEST_ID
        );
    }
}