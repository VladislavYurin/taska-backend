package ru.taska.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.OutboxProcessingProperties;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;
import ru.taska.dto.ProblematicEventCountDto;
import ru.taska.dto.ProblematicOutboxEventResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProblematicOutboxEventMapper;
import ru.taska.repository.ProblematicOutboxEventRepository;
import ru.taska.service.readonly.ProblematicOutboxEventServiceImpl;
import ru.taska.service.readonly.SensitiveDataMaskService;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblematicOutboxEventServiceImplTest {

    private static final String SERVICE_A = "service-a";
    private static final String SERVICE_B = "service-b";
    private static final String REQUEST_ID = "test-request-id";
    private static final String NODE_ID = "test-node-id";
    private static final int MAX_SIZE = 100;

    @Mock
    private OutboxProcessingProperties outboxProperties;

    @Mock
    private ProblematicOutboxEventRepository outboxEventRepository;

    @Mock
    private ProblematicOutboxEventMapper problematicOutboxEventMapper;

    @Mock
    private SensitiveDataMaskService maskService;

    @Captor
    private ArgumentCaptor<OffsetDateTime> processingCutoffCaptor;

    @Captor
    private ArgumentCaptor<OffsetDateTime> newCutoffCaptor;

    private ProblematicOutboxEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProblematicOutboxEventServiceImpl(
                outboxProperties, outboxEventRepository, problematicOutboxEventMapper, maskService
        );
    }

    @Test
    void shouldThrowDomainExceptionWhenServiceKeyIsUnknown() {
        when(outboxProperties.services()).thenReturn(List.of(SERVICE_A, SERVICE_B));

        StepVerifier.create(service.getProblematicOutboxEventsSummary("unknown-service", REQUEST_ID, NODE_ID))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(DomainException.class);
                    DomainException domainException = (DomainException) error;
                    assertThat(domainException.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(domainException.getMessage()).contains("unknown-service");
                })
                .verify();
    }

    @Test
    void shouldQueryAllServicesWhenServiceKeyIsNull() {
        stubProperties();
        stubCountsForAllServices();
        stubEventsForAllServices();

        StepVerifier.create(service.getProblematicOutboxEventsSummary(null, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.counts()).hasSize(2);
                    assertThat(response.events()).hasSize(2);
                })
                .verifyComplete();

        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(MAX_SIZE + 1));
        verify(outboxEventRepository).fetchProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(MAX_SIZE + 1));
    }

    @Test
    void shouldQueryAllServicesWhenServiceKeyIsEmpty() {
        stubProperties();
        stubCountsForAllServices();
        stubEventsForAllServices();

        StepVerifier.create(service.getProblematicOutboxEventsSummary("", REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.counts()).hasSize(2);
                    assertThat(response.events()).hasSize(2);
                })
                .verifyComplete();

        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(MAX_SIZE + 1));
        verify(outboxEventRepository).fetchProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(MAX_SIZE + 1));
    }

    @Test
    void shouldQueryOnlySpecifiedServiceForEventsButAllForCounts() {
        stubProperties();
        stubCountsForAllServices();
        stubEventsForService(SERVICE_A);

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.counts()).hasSize(2);
                    assertThat(response.events()).hasSize(1);
                    assertThat(response.events().get(0).serviceKey()).isEqualTo(SERVICE_A);
                })
                .verifyComplete();

        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).countProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(outboxEventRepository).fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), eq(MAX_SIZE + 1));
        verify(outboxEventRepository, never()).fetchProblematicEvents(eq(SERVICE_B), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt());
    }

    @Test
    void shouldSortEventsByCreatedAtAcrossServices() {
        stubProperties();
        stubCountsForAllServices();

        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        Instant middle = Instant.parse("2026-01-02T00:00:00Z");
        Instant newest = Instant.parse("2026-01-03T00:00:00Z");

        stubEventsWithTimestamps(SERVICE_A, List.of(oldest, newest));
        stubEventsWithTimestamps(SERVICE_B, List.of(middle));

        StepVerifier.create(service.getProblematicOutboxEventsSummary(null, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.events()).hasSize(3);
                    assertThat(response.events().get(0).createdAt()).isEqualTo(oldest);
                    assertThat(response.events().get(1).createdAt()).isEqualTo(middle);
                    assertThat(response.events().get(2).createdAt()).isEqualTo(newest);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyEventsAndZeroCountsWhenNoProblematicEvents() {
        stubSingleServiceProperties(SERVICE_A);

        when(outboxEventRepository.countProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Mono.empty());
        when(outboxEventRepository.fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.events()).isEmpty();
                    assertThat(response.counts()).hasSize(1);
                    ProblematicEventCountDto count = response.counts().get(0);
                    assertThat(count.serviceKey()).isEqualTo(SERVICE_A);
                    assertThat(count.failedCount()).isZero();
                    assertThat(count.stuckProcessingCount()).isZero();
                    assertThat(count.overdueNewCount()).isZero();
                    assertThat(response.notAllShown()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotAllShownFalseWhenEventsLessThanLimit() {
        int maxSize = 5;
        stubSingleServiceProperties(SERVICE_A, maxSize);
        stubCountsForService(SERVICE_A);

        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        stubEventsWithTimestamps(SERVICE_A, List.of(t1, t2));

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.events()).hasSize(2);
                    assertThat(response.notAllShown()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotAllShownFalseWhenEventsExactlyMaxSize() {
        int maxSize = 2;
        stubSingleServiceProperties(SERVICE_A, maxSize);
        stubCountsForService(SERVICE_A);

        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        stubEventsWithTimestamps(SERVICE_A, List.of(t1, t2));

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.events()).hasSize(2);
                    assertThat(response.notAllShown()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void shouldTrimEventsAndSetNotAllShownTrueWhenExceedsMaxSize() {
        int maxSize = 2;
        stubSingleServiceProperties(SERVICE_A, maxSize);
        stubCountsForService(SERVICE_A);

        Instant oldest = Instant.parse("2026-01-01T00:00:00Z");
        Instant middle = Instant.parse("2026-01-02T00:00:00Z");
        Instant newest = Instant.parse("2026-01-03T00:00:00Z");
        stubEventsWithTimestamps(SERVICE_A, List.of(oldest, middle, newest));

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> {
                    assertThat(response.notAllShown()).isTrue();
                    assertThat(response.events()).hasSize(2);
                    assertThat(response.events().get(0).createdAt()).isEqualTo(oldest);
                    assertThat(response.events().get(1).createdAt()).isEqualTo(middle);
                })
                .verifyComplete();
    }

    @Test
    void shouldPassCorrectCutoffValuesToRepository() {
        Duration processingTimeout = Duration.ofMinutes(7);
        Duration overdueThreshold = Duration.ofMinutes(15);

        when(outboxProperties.services()).thenReturn(List.of(SERVICE_A));
        when(outboxProperties.maxProblematicListSize()).thenReturn(MAX_SIZE);
        when(outboxProperties.processingTimeouts()).thenReturn(Map.of(SERVICE_A, processingTimeout));
        when(outboxProperties.overdueNewThresholds()).thenReturn(Map.of(SERVICE_A, overdueThreshold));

        Map<String, Object> countRow = Map.of("failed_count", 0L, "stuck_count", 0L, "overdue_count", 0L);
        when(outboxEventRepository.countProblematicEvents(eq(SERVICE_A), processingCutoffCaptor.capture(), newCutoffCaptor.capture()))
                .thenReturn(Mono.just(countRow));
        when(outboxEventRepository.fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.empty());

        Instant before = Instant.now();

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> assertThat(response).isNotNull())
                .verifyComplete();

        Instant after = Instant.now();

        OffsetDateTime capturedProcessingCutoff = processingCutoffCaptor.getValue();
        OffsetDateTime capturedNewCutoff = newCutoffCaptor.getValue();

        OffsetDateTime expectedProcessingMin = OffsetDateTime.ofInstant(before.minus(processingTimeout), ZoneOffset.UTC);
        OffsetDateTime expectedProcessingMax = OffsetDateTime.ofInstant(after.minus(processingTimeout), ZoneOffset.UTC);
        assertThat(capturedProcessingCutoff).isBetween(expectedProcessingMin, expectedProcessingMax);

        OffsetDateTime expectedNewMin = OffsetDateTime.ofInstant(before.minus(overdueThreshold), ZoneOffset.UTC);
        OffsetDateTime expectedNewMax = OffsetDateTime.ofInstant(after.minus(overdueThreshold), ZoneOffset.UTC);
        assertThat(capturedNewCutoff).isBetween(expectedNewMin, expectedNewMax);
    }

    @Test
    void shouldCallMaskServiceAndPassMaskedRowToMapper() {
        stubSingleServiceProperties(SERVICE_A);
        stubCountsForService(SERVICE_A);

        Map<String, Object> originalRow = new LinkedHashMap<>();
        originalRow.put("id", "evt-1");
        originalRow.put("aggregate_type", "Order");
        originalRow.put("aggregate_id", "order-1");
        originalRow.put("event_type", "OrderCreated");
        originalRow.put("payload", "{\"secret\":\"value\"}");
        originalRow.put("status", "FAILED");
        originalRow.put("created_at", Instant.parse("2026-01-01T00:00:00Z"));
        originalRow.put("attempts", 3);

        Map<String, Object> maskedRow = new LinkedHashMap<>(originalRow);
        maskedRow.put("payload", "***");

        when(outboxEventRepository.fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.just(originalRow));

        when(maskService.maskSensitiveData(eq(List.of(originalRow)), eq(SERVICE_A), eq("outbox_events"), eq(REQUEST_ID), eq(NODE_ID)))
                .thenReturn(List.of(maskedRow));

        ProblematicOutboxEventResponseDto dto = new ProblematicOutboxEventResponseDto(
                "evt-1", "Order", "order-1", "OrderCreated", "***",
                "FAILED", Instant.parse("2026-01-01T00:00:00Z"), null,
                3, null, null, null, SERVICE_A, "Event processing failed"
        );
        when(problematicOutboxEventMapper.toDto(any(), eq(SERVICE_A), anyString()))
                .thenReturn(dto);

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .assertNext(response -> assertThat(response.events()).hasSize(1))
                .verifyComplete();

        verify(maskService).maskSensitiveData(eq(List.of(originalRow)), eq(SERVICE_A), eq("outbox_events"), eq(REQUEST_ID), eq(NODE_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> rowCaptor = ArgumentCaptor.forClass(Map.class);
        verify(problematicOutboxEventMapper).toDto(rowCaptor.capture(), eq(SERVICE_A), anyString());
        assertThat(rowCaptor.getValue().get("payload")).isEqualTo("***");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DONE", "COMPLETED", "UNKNOWN"})
    void shouldThrowDomainExceptionForUnexpectedStatus(String status) {
        stubSingleServiceProperties(SERVICE_A);
        stubCountsForService(SERVICE_A);

        Map<String, Object> eventRow = new HashMap<>();
        eventRow.put("id", "evt-42");
        eventRow.put("aggregate_type", "Order");
        eventRow.put("aggregate_id", "order-1");
        eventRow.put("event_type", "OrderCreated");
        eventRow.put("payload", "{}");
        eventRow.put("status", status);
        eventRow.put("created_at", Instant.parse("2026-01-01T00:00:00Z"));
        eventRow.put("attempts", 3);

        when(outboxEventRepository.fetchProblematicEvents(eq(SERVICE_A), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.just(eventRow));
        when(maskService.maskSensitiveData(anyList(), eq(SERVICE_A), eq("outbox_events"), eq(REQUEST_ID), eq(NODE_ID)))
                .thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(service.getProblematicOutboxEventsSummary(SERVICE_A, REQUEST_ID, NODE_ID))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(DomainException.class);
                    DomainException domainException = (DomainException) error;
                    assertThat(domainException.getStatus()).isEqualTo(DomainStatus.INTERNAL);
                    assertThat(domainException.getMessage()).contains("evt-42");
                    assertThat(domainException.getMessage()).contains(String.valueOf(status));
                })
                .verify();
    }

    private void stubProperties() {
        when(outboxProperties.services()).thenReturn(List.of(SERVICE_A, SERVICE_B));
        when(outboxProperties.maxProblematicListSize()).thenReturn(MAX_SIZE);
        when(outboxProperties.processingTimeouts()).thenReturn(Map.of(
                SERVICE_A, Duration.ofMinutes(5),
                SERVICE_B, Duration.ofMinutes(5)
        ));
        when(outboxProperties.overdueNewThresholds()).thenReturn(Map.of(
                SERVICE_A, Duration.ofMinutes(10),
                SERVICE_B, Duration.ofMinutes(10)
        ));
    }

    private void stubCountsForAllServices() {
        Map<String, Object> countRow = Map.of(
                "failed_count", 1L,
                "stuck_count", 2L,
                "overdue_count", 3L
        );
        when(outboxEventRepository.countProblematicEvents(anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Mono.just(countRow));
    }

    private void stubEventsForAllServices() {
        stubEventsForService(SERVICE_A);
        stubEventsForService(SERVICE_B);
    }

    private void stubEventsForService(String serviceKey) {
        Map<String, Object> eventRow = Map.of(
                "id", "evt-1",
                "aggregate_type", "Order",
                "aggregate_id", "order-1",
                "event_type", "OrderCreated",
                "payload", "{}",
                "status", "FAILED",
                "created_at", Instant.parse("2026-01-01T00:00:00Z"),
                "attempts", 3
        );

        when(outboxEventRepository.fetchProblematicEvents(eq(serviceKey), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.just(eventRow));

        when(maskService.maskSensitiveData(anyList(), eq(serviceKey), eq("outbox_events"), eq(REQUEST_ID), eq(NODE_ID)))
                .thenReturn(List.of(eventRow));

        ProblematicOutboxEventResponseDto dto = new ProblematicOutboxEventResponseDto(
                "evt-1", "Order", "order-1", "OrderCreated", "{}",
                "FAILED", Instant.parse("2026-01-01T00:00:00Z"), null,
                3, null, null, null, serviceKey, "Event processing failed"
        );

        when(problematicOutboxEventMapper.toDto(any(), eq(serviceKey), anyString()))
                .thenReturn(dto);
    }

    private void stubSingleServiceProperties(String serviceKey) {
        stubSingleServiceProperties(serviceKey, MAX_SIZE);
    }

    private void stubSingleServiceProperties(String serviceKey, int maxSize) {
        when(outboxProperties.services()).thenReturn(List.of(serviceKey));
        when(outboxProperties.maxProblematicListSize()).thenReturn(maxSize);
        when(outboxProperties.processingTimeouts()).thenReturn(Map.of(serviceKey, Duration.ofMinutes(5)));
        when(outboxProperties.overdueNewThresholds()).thenReturn(Map.of(serviceKey, Duration.ofMinutes(10)));
    }

    private void stubCountsForService(String serviceKey) {
        Map<String, Object> countRow = Map.of(
                "failed_count", 1L,
                "stuck_count", 2L,
                "overdue_count", 3L
        );
        when(outboxEventRepository.countProblematicEvents(eq(serviceKey), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Mono.just(countRow));
    }

    private void stubEventsWithTimestamps(String serviceKey, List<Instant> timestamps) {
        List<Map<String, Object>> eventRows = timestamps.stream()
                .map(ts -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", "evt-" + ts.getEpochSecond());
                    row.put("aggregate_type", "Order");
                    row.put("aggregate_id", "order-1");
                    row.put("event_type", "OrderCreated");
                    row.put("payload", "{}");
                    row.put("status", "FAILED");
                    row.put("created_at", ts);
                    row.put("attempts", 3);
                    return row;
                })
                .toList();

        when(outboxEventRepository.fetchProblematicEvents(eq(serviceKey), any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(Flux.fromIterable(eventRows));

        when(maskService.maskSensitiveData(anyList(), eq(serviceKey), eq("outbox_events"), eq(REQUEST_ID), eq(NODE_ID)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<Instant, ProblematicOutboxEventResponseDto> dtoByTimestamp = new HashMap<>();
        for (Instant ts : timestamps) {
            dtoByTimestamp.put(ts, new ProblematicOutboxEventResponseDto(
                    "evt-" + ts.getEpochSecond(), "Order", "order-1", "OrderCreated", "{}",
                    "FAILED", ts, null,
                    3, null, null, null, serviceKey, "Event processing failed"
            ));
        }

        when(problematicOutboxEventMapper.toDto(any(), eq(serviceKey), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> row = inv.getArgument(0);
                    Instant ts = (Instant) row.get("created_at");
                    return dtoByTimestamp.get(ts);
                });
    }
}
