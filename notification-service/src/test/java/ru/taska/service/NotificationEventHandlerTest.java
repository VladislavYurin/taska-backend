package ru.taska.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NotificationEventHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private NotificationEventHandler handler;

    @Test
    void shouldNotCreateDuplicatesOnRepeatedDelivery() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode payload = objectMapper.readTree("""
                {
                  "assigneeId": "00000000-0000-0000-0000-000000000001"
                }
                """);

        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID issueId = UUID.fromString("00000000-0000-0000-0000-000000000010");

        TaskaEvent event = new TaskaEvent(
                eventId,
                "ISSUE",
                issueId,
                "IssueAssigned",
                payload
        );

        when(processedEventRepository.existsById(eventId.toString()))
                .thenReturn(Mono.just(false))
                .thenReturn(Mono.just(true));
        when(processedEventRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));
        when(notificationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        // первая доставка
        handler.handle(event).block();
        // повторная доставка того же события
        handler.handle(event).block();

        verify(processedEventRepository, times(2)).existsById(eventId.toString());
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
        verify(notificationRepository, times(1)).save(any(Notification.class));
        verifyNoMoreInteractions(notificationRepository);
    }
}

