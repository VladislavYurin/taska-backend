package ru.taska.kafka;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class OutboxEventSchedulerTest {

    @Mock
    private OutboxEventProcessor processor;

    @InjectMocks
    private OutboxEventScheduler scheduler;

    @Nested
    class PublishOutboxEvents {

        @Test
        @DisplayName("Должен запустить обработку outbox-событий")
        void shouldCallProcessor() {
            Mockito.when(processor.processOutboxEvents())
                   .thenReturn(Mono.empty());

            scheduler.publishOutboxEvents();

            Mockito.verify(processor).processOutboxEvents();
        }

        @Test
        @DisplayName("Должен перехватывать ошибку обработки событий")
        void shouldHandleException() {
            Mockito.when(processor.processOutboxEvents())
                   .thenReturn(Mono.error(new RuntimeException("Kafka unavailable")));

            Assertions.assertDoesNotThrow(() -> scheduler.publishOutboxEvents());

            Mockito.verify(processor).processOutboxEvents();
        }
    }

    @Nested
    class RecoverStuckEvents {

        @Test
        @DisplayName("Должен запустить восстановление застрявших событий")
        void shouldCallProcessor() {
            Mockito.when(processor.processStuckEvents())
                   .thenReturn(Mono.empty());

            scheduler.recoverStuckEvents();

            Mockito.verify(processor).processStuckEvents();
        }

        @Test
        @DisplayName("Должен перехватывать ошибку восстановления застрявших событий")
        void shouldHandleException() {
            Mockito.when(processor.processStuckEvents())
                   .thenReturn(Mono.error(new RuntimeException("DB error")));

            Assertions.assertDoesNotThrow(() -> scheduler.recoverStuckEvents());

            Mockito.verify(processor).processStuckEvents();
        }
    }
}
