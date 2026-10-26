package ru.taska.service.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.OutboxProcessingProperties;
import ru.taska.config.props.OutboxRetryProperties;
import ru.taska.mapper.ProblematicOutboxEventMapper;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxRetryRepository;
import ru.taska.service.AuditService;
import tools.jackson.databind.ObjectMapper;
import ru.taska.repository.ProblematicOutboxEventRepository;
import ru.taska.service.readonly.ProblematicOutboxEventServiceImpl;
import ru.taska.service.readonly.SensitiveDataMaskService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

class OutboxRetryableSummaryTest {
    @ParameterizedTest
    @CsvSource({"10,PROCESSING,7,false", "10,PROCESSING,11,true", "5,PROCESSING,7,true",
            "10,FAILED,0,true", "10,NEW,100,false", "10,PROCESSING,-1,false"})
    void summaryUsesRetryThresholdAndPreservesFlagInProto(int threshold, String status, int ageMinutes, boolean expected) {
        var repository = Mockito.mock(ProblematicOutboxEventRepository.class);
        var mask = Mockito.mock(SensitiveDataMaskService.class);
        var mapper = new ProblematicOutboxEventMapper();
        var properties = new OutboxProcessingProperties(Map.of("issue", Duration.ofMinutes(5)),
                Duration.ofMinutes(30), List.of("issue"), 100);
        var retryProperties = new OutboxRetryProperties(Duration.ofMinutes(threshold));
        var service = new ProblematicOutboxEventServiceImpl(properties, repository, mapper, mask, retryProperties);
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("aggregate_id", UUID.randomUUID());
        row.put("aggregate_type", "Issue");
        row.put("event_type", "IssueCreated");
        row.put("payload", "{}");
        row.put("status", status);
        row.put("attempts", 1);
        row.put("created_at", Instant.now().minusSeconds(7200));
        row.put("processing_started_at", ageMinutes < 0 ? null : Instant.now().minusSeconds(ageMinutes * 60L));
        Mockito.when(repository.countProblematicEvents(anyString(), any(), any()))
                .thenReturn(Mono.just(Map.of("failed_count", 1L, "stuck_count", 1L, "overdue_count", 0L)));
        Mockito.when(repository.fetchProblematicEvents(anyString(), any(), any(), anyInt())).thenReturn(Flux.just(row));
        Mockito.when(mask.maskSensitiveData(anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StepVerifier.create(service.getProblematicOutboxEventsSummary("issue", "request", "admin"))
                .assertNext(response -> {
                    assertThat(response.events().getFirst().retryable()).isEqualTo(expected);
                    assertThat(mapper.toProto(response).getEvents(0).getRetryable()).isEqualTo(expected);
                }).verifyComplete();

        // Та же конфигурация должна давать то же решение в настоящем сервисе команды retry.
        var retryRepository = Mockito.mock(OutboxRetryRepository.class);
        var json = new ObjectMapper();
        var snapshot = new OutboxEventSnapshot((UUID) row.get("id"), "Issue", (UUID) row.get("aggregate_id"),
                "IssueCreated", OutboxStatus.valueOf(status), json.createObjectNode(), 1, null,
                (Instant) row.get("created_at"), null, (Instant) row.get("processing_started_at"), "request");
        Mockito.when(retryRepository.findById("issue", snapshot.id())).thenReturn(Mono.just(snapshot));
        var reachedAtomicRetry = new IllegalStateException("Reached atomic retry");
        Mockito.when(retryRepository.retry(anyString(), any(), any())).thenReturn(Mono.error(reachedAtomicRetry));
        var retryService = new OutboxRetryServiceImpl(retryRepository, Mockito.mock(AuditService.class), retryProperties, json, Mockito.mock(SensitiveDataMaskService.class));
        StepVerifier.create(retryService.retryOutboxEvent("request", "admin", "issue", snapshot.id(),
                        "test", UUID.randomUUID(), "admin",
                "admin@example.com", json.createArrayNode()))
                .expectErrorMatches(error -> expected ? error == reachedAtomicRetry
                        : error instanceof DomainException domain && domain.getStatus() == DomainStatus.FAILED_PRECONDITION)
                .verify();
        Mockito.verify(retryRepository, Mockito.times(expected ? 1 : 0)).retry(anyString(), any(), any());
    }
}
