package ru.taska.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
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

    @Mock
    private NotificationFactory notificationFactory;

    @InjectMocks
    private NotificationEventHandlerImpl handler;

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private JsonNode payload(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private Notification notification(UUID userId) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
    }

    @Test
    void shouldNotCreateDuplicatesOnRepeatedDelivery() throws Exception {
        TaskaEvent event = new TaskaEvent(
                EVENT_ID,
                "ISSUE",
                ISSUE_ID,
                "IssueAssigned",
                payload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """)
        );

        when(notificationFactory.create(any(), any()))
                .thenReturn(List.of(notification(ASSIGNEE_ID)));
        when(processedEventRepository.existsById(EVENT_ID.toString()))
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

        verify(processedEventRepository, times(2)).existsById(EVENT_ID.toString());
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
        verify(notificationRepository, times(1)).save(any(Notification.class));
        verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    void shouldSkipNullEvent() {
        handler.handle(null).block();

        verify(processedEventRepository, never()).existsById(any(String.class));
        verify(processedEventRepository, never()).save(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void shouldSkipEventWithNullId() throws Exception {
        TaskaEvent event = new TaskaEvent(
                null,
                "ISSUE",
                ISSUE_ID,
                "IssueAssigned",
                payload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """)
        );

        handler.handle(event).block();

        verify(processedEventRepository, never()).existsById(any(String.class));
        verify(processedEventRepository, never()).save(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void shouldSkipAlreadyProcessedEvent() throws Exception {
        TaskaEvent event = new TaskaEvent(
                EVENT_ID,
                "ISSUE",
                ISSUE_ID,
                "IssueAssigned",
                payload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """)
        );

        when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(true));

        handler.handle(event).block();

        verify(processedEventRepository, times(1)).existsById(EVENT_ID.toString());
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void shouldSaveProcessedEventAndNotificationFirstDelivery() throws Exception {
        TaskaEvent event = new TaskaEvent(
                EVENT_ID,
                "ISSUE",
                ISSUE_ID,
                "IssueAssigned",
                payload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """)
        );

        when(notificationFactory.create(any(), any()))
                .thenReturn(List.of(notification(ASSIGNEE_ID)));
        when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(false));
        when(processedEventRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));
        when(notificationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        handler.handle(event).block();

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
        verify(notificationRepository, times(1)).save(any(Notification.class));

    }

    @Test
    void shouldCreateTwoNotificationsForIssueTransitioned() throws Exception {
        TaskaEvent event = new TaskaEvent(
                EVENT_ID,
                "ISSUE",
                ISSUE_ID,
                "IssueTransitioned",
                payload("""
                        {
                          "reporterId": "00000000-0000-0000-0000-000000000002",
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """)
        );

        when(notificationFactory.create(any(), any()))
                .thenReturn(List.of(notification(REPORTER_ID), notification(ASSIGNEE_ID)));
        when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(false));
        when(processedEventRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));
        when(notificationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        handler.handle(event).block();

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }
}

