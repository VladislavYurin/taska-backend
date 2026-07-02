package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
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

    @Mock
    private TransactionalOperator transactionalOperator;

    @InjectMocks
    private NotificationEventHandlerImpl handler;

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void shouldNotCreateDuplicatesOnRepeatedDelivery() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Notification notification = buildNotification(ASSIGNEE_ID);

        Mockito.when(processedEventRepository.save(Mockito.any(ProcessedEvent.class)))
                .thenReturn(Mono.just(new ProcessedEvent()))
                .thenReturn(Mono.error(new DuplicateKeyException("duplicate key error")));

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(notification));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(event))
                .verifyComplete();

        StepVerifier.create(handler.handle(event))
                .verifyComplete();

        Mockito.verify(processedEventRepository, Mockito.times(2))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID);

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
                .save(ArgumentMatchers.any());

        Mockito.verify(notificationFactory, Mockito.never())
                .create(Mockito.any(), Mockito.any());

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(transactionalOperator, Mockito.never())
                .transactional(Mockito.any(Mono.class));

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoInteractions(notificationFactory);
    }

    @Test
    void shouldSkipEventWithNullId() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(null)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(notificationFactory, Mockito.never())
                .create(Mockito.any(), Mockito.any());

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any());

        Mockito.verify(transactionalOperator, Mockito.never())
                .transactional(Mockito.any(Mono.class));

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));

        Mockito.verifyNoInteractions(notificationFactory);
    }

    @Test
    void shouldIgnoreDuplicateEvent() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Mockito.when(processedEventRepository.save(Mockito.any(ProcessedEvent.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("duplicate key error")));

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StepVerifier.create(handler.handle(event))
                .verifyComplete();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(Mockito.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.never())
                .create(Mockito.any(), Mockito.any());

        Mockito.verify(notificationRepository, Mockito.never())
                .save(Mockito.any(Notification.class));

        Mockito.verify(transactionalOperator, Mockito.times(1))
                .transactional(Mockito.any(Mono.class));

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(Mockito.any(Notification.class));
    }

    @Test
    void shouldSaveProcessedEventNotificationAndSendEmailFirstDelivery() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Mockito.when(processedEventRepository.save(ArgumentMatchers.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(buildNotification(ASSIGNEE_ID)));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID);

        Mockito.verify(notificationRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(transactionalOperator, Mockito.times(1))
                .transactional(Mockito.any(Mono.class));

        Mockito.verify(emailSenderService, Mockito.times(1))
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));
    }

    @Test
    void shouldCreateTwoNotificationsAndSendTwoEmailsForIssueTransitioned() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("IssueTransitioned")
                .payload(buildPayload("""
                        {
                          "reporterId": "00000000-0000-0000-0000-000000000002",
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Mockito.when(processedEventRepository.save(ArgumentMatchers.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationFactory.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(buildNotification(REPORTER_ID), buildNotification(ASSIGNEE_ID)));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Mockito.when(emailSenderService.sendIfEnabled(ArgumentMatchers.any(Notification.class)))
                .thenReturn(Mono.empty());

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(ArgumentMatchers.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID);

        Mockito.verify(notificationRepository, Mockito.times(2))
                .save(ArgumentMatchers.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.times(2))
                .sendIfEnabled(ArgumentMatchers.any(Notification.class));
    }

    @Test
    void shouldNotSendEmailWhenNotificationsNotCreated() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("ISSUE")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Mockito.when(processedEventRepository.save(Mockito.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationFactory.create(Mockito.any(), Mockito.any()))
                .thenReturn(Collections.emptyList());

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(event).block();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(Mockito.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID);

        Mockito.verify(notificationRepository, Mockito.never())
                .save(Mockito.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.never())
                .sendIfEnabled(Mockito.any(Notification.class));
    }

    @Test
    void shouldCompleteSuccessfullyWhenEmailSendingFails() throws Exception {
        TaskaEvent event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("ISSUE")
                .aggregateId(ISSUE_ID)
                .eventType("IssueAssigned")
                .payload(buildPayload("""
                        {
                          "assigneeId": "00000000-0000-0000-0000-000000000001"
                        }
                        """))
                .build();

        Notification notification = buildNotification(ASSIGNEE_ID);

        Mockito.when(processedEventRepository.save(Mockito.any(ProcessedEvent.class)))
                .thenAnswer(invocation -> Mono.just((ProcessedEvent) invocation.getArgument(0)));

        Mockito.when(notificationFactory.create(Mockito.any(), Mockito.any()))
                .thenReturn(List.of(notification));

        Mockito.when(notificationRepository.save(Mockito.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        Mockito.when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Mockito.when(emailSenderService.sendIfEnabled(Mockito.any(Notification.class)))
                .thenReturn(Mono.error(new RuntimeException("Email sending failed")));

        StepVerifier.create(handler.handle(event))
                .verifyComplete();

        Mockito.verify(processedEventRepository, Mockito.times(1))
                .save(Mockito.any(ProcessedEvent.class));

        Mockito.verify(notificationFactory, Mockito.times(1))
                .create(event, EVENT_ID);

        Mockito.verify(notificationRepository, Mockito.times(1))
                .save(Mockito.any(Notification.class));

        Mockito.verify(emailSenderService, Mockito.times(1))
                .sendIfEnabled(Mockito.any(Notification.class));
    }

    private JsonNode buildPayload(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private Notification buildNotification(UUID userId) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
    }
}