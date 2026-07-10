package ru.taska.kafka;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.taska.transport.kafka.OutboxEventProcessor;
import ru.taska.transport.kafka.OutboxEventScheduler;

@ExtendWith(MockitoExtension.class)
class OutboxEventSchedulerTest {

    @Mock
    private OutboxEventProcessor processor;

    @InjectMocks
    private OutboxEventScheduler scheduler;

    @Test
    @DisplayName("Должен запустить обработку событий")
    void publishOutboxEvents_shouldCallProcessor() {
        Mockito.when(processor.processOutboxEvents())
                .thenReturn(Mono.empty());

        scheduler.publishOutboxEvents();

        Mockito.verify(processor).processOutboxEvents();
    }

    @Test
    @DisplayName("Должен перехватывать исключение при возникновении ошибки обработки событий")
    void publishOutboxEvents_shouldHandleException() {
        Mockito.when(processor.processOutboxEvents())
                .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

        Assertions.assertDoesNotThrow(() -> scheduler.publishOutboxEvents());

        Mockito.verify(processor).processOutboxEvents();
    }

    @Test
    @DisplayName("Должен запустить восстановление зависших событий")
    void recoverStuckEvents_shouldCallProcessor() {
        Mockito.when(processor.processStuckEvents())
                .thenReturn(Mono.empty());

        scheduler.recoverStuckEvents();

        Mockito.verify(processor).processStuckEvents();
    }

    @Test
    @DisplayName("Должен перехватывать исключение при возникновении ошибки обработки зависших событий")
    void recoverStuckEvents_shouldHandleException() {
        Mockito.when(processor.processStuckEvents())
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        Assertions.assertDoesNotThrow(() -> scheduler.recoverStuckEvents());

        Mockito.verify(processor).processStuckEvents();
    }
}
