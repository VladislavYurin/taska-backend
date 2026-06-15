package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class NotificationEventHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationFactory notificationFactory;

    @Mock
    private EmailSenderService emailSenderService;

    @InjectMocks
    private NotificationEventHandlerImpl handler;

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

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

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(notification(ASSIGNEE_ID)));

        Mockito.when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(false))
                .thenReturn(Mono.just(true));

        Mockito.when(processedEventRepository.save(ArgumentMatchers.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        handler.handle(event).block();
        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(2))
                .existsById(EVENT_ID.toString());

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID.toString());

        Mockito.verify(notificationRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.times(1))
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoMoreInteractions(notificationRepository);
    }

    @Test
    void shouldSkipNullEvent() {
        handler.handle(null).block();

        Mockito.verify(processedEventRepository, Mockito.never())
                .existsById(ArgumentMatchers.any(String.class));

        Mockito.verify(processedEventRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoInteractions(notificationFactory);
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

        Mockito.verify(processedEventRepository, Mockito.never())
                .existsById(ArgumentMatchers.any(String.class));

        Mockito.verify(processedEventRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoInteractions(notificationFactory);
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

        Mockito.when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(true));

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .existsById(EVENT_ID.toString());

        Mockito.verify(processedEventRepository, Mockito.never())
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoInteractions(notificationFactory);
    }

    @Test
    void shouldSaveProcessedEventNotificationAndSendEmailFirstDelivery() throws Exception {
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

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(notification(ASSIGNEE_ID)));

        Mockito.when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(false));

        Mockito.when(processedEventRepository.save(ArgumentMatchers.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID.toString());

        Mockito.verify(notificationRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.times(1))
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));
    }

    @Test
    void shouldCreateTwoNotificationsAndSendTwoEmailsForIssueTransitioned() throws Exception {
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

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(notification(REPORTER_ID), notification(ASSIGNEE_ID)));

        Mockito.when(processedEventRepository.existsById(EVENT_ID.toString()))
                .thenReturn(Mono.just(false));

        Mockito.when(processedEventRepository.save(ArgumentMatchers.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID.toString());

        Mockito.verify(notificationRepository, Mockito.times(2))
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.times(2))
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));
    }

    private JsonNode payload(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private Notification notification(UUID userId) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
    }
}