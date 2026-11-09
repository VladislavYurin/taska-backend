package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.entity.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.service.impl.OutboxEventServiceImpl;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.node.JsonNodeFactory;

class OutboxEventServiceImplTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final PayloadSerializer serializer = mock(PayloadSerializer.class);
    private final VerifiedTenantContext tenantContext = mock(VerifiedTenantContext.class);
    private OutboxEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OutboxEventServiceImpl(repository, serializer);
    }

    @Test
    void rejectsEventBeforeSaveWhenVerifiedTenantIsMissing() {
        StepVerifier.create(saveEvent())
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Verified context required"))
                .verify();

        verifyNoInteractions(repository);
    }

    @Test
    void storesVerifiedTenantWithEvent() {
        var tenantId = UUID.randomUUID();
        when(tenantContext.tenantId()).thenReturn(tenantId.toString());
        when(repository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var saved = saveEvent()
                .contextWrite(context -> context.put(VerifiedTenantContext.class, tenantContext))
                .block();

        assertThat(saved).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        verify(repository).save(any(OutboxEvent.class));
    }

    private Mono<OutboxEvent> saveEvent() {
        return service.saveOutboxEvent(
                "request-id",
                "node-id",
                AggregateType.ISSUE,
                UUID.randomUUID(),
                EventType.ISSUE_UPDATED,
                JsonNodeFactory.instance.objectNode());
    }
}
