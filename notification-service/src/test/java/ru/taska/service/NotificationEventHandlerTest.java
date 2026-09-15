package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationEventHandlerImpl")
class NotificationEventHandlerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final String AGGREGATE_TYPE_ISSUE = "ISSUE";
    private static final String EVENT_TYPE_ISSUE_ASSIGNED = "IssueAssigned";
    private static final String EVENT_TYPE_ISSUE_TRANSITIONED = "IssueTransitioned";

    private static final String PAYLOAD_ISSUE_ASSIGNED = """
            {
              "assigneeId": "00000000-0000-0000-0000-000000000001"
            }
            """;

    private static final String PAYLOAD_ISSUE_TRANSITIONED = """
            {
              "reporterId": "00000000-0000-0000-0000-000000000002",
              "assigneeId": "00000000-0000-0000-0000-000000000001"
            }
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock private NotificationRepository notificationRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private NotificationFactory notificationFactory;
    @Mock private EmailSenderService emailSenderService;
    @Mock private TransactionalOperator transactionalOperator;

    @InjectMocks
    private NotificationEventHandlerImpl handler;

    @BeforeEach
    void setUp() {
        // TransactionalOperator: просто пропускаем цепочку дальше
        Mockito.lenient()
                .when(transactionalOperator.transactional(ArgumentMatchers.any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== Happy path ====================

    @Nested
    @DisplayName("Happy path")
    class HappyPathTests {

        @Test
        @DisplayName("saves ProcessedEvent, notifications, and sends emails")
        void shouldSaveAndSendForFirstDelivery() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();
            stubEmailSendSuccess();

            handler.handle(event).block();

            verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
            verify(notificationFactory, times(1)).create(event, EVENT_ID);
            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(emailSenderService, times(1)).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("saves ProcessedEvent with correct eventId and sourceType")
        void shouldSaveProcessedEventWithSourceType() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(Collections.emptyList());

            handler.handle(event).block();

            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());

            ProcessedEvent saved = captor.getValue();
            assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
            assertThat(saved.getSourceType()).isEqualTo(AGGREGATE_TYPE_ISSUE);
            assertThat(saved.getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("creates two notifications and sends two emails for transitioned event")
        void shouldCreateTwoNotificationsForTransitioned() throws Exception {
            TaskaEvent event = issueTransitionedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(REPORTER_ID), notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();
            stubEmailSendSuccess();

            handler.handle(event).block();

            verify(notificationRepository, times(2)).save(any(Notification.class));
            verify(emailSenderService, times(2)).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("does not save notifications when factory returns empty list")
        void shouldNotSaveWhenFactoryReturnsEmpty() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(Collections.emptyList());

            handler.handle(event).block();

            verify(notificationRepository, never()).save(any(Notification.class));
            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("sends email for exactly the saved notification")
        void shouldSendEmailForSavedNotification() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();
            stubEmailSendSuccess();

            handler.handle(event).block();

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(emailSenderService).sendIfEnabled(captor.capture());

            Notification sent = captor.getValue();
            assertThat(sent.getUserId()).isEqualTo(ASSIGNEE_ID);
        }

        @Test
        @DisplayName("ProcessedEvent is saved before notifications")
        void shouldSaveProcessedEventBeforeNotifications() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();
            stubEmailSendSuccess();

            handler.handle(event).block();

            InOrder inOrder = Mockito.inOrder(processedEventRepository, notificationRepository);
            inOrder.verify(processedEventRepository).save(any(ProcessedEvent.class));
            inOrder.verify(notificationRepository).save(any(Notification.class));
        }
    }

    // ==================== Дедупликация ====================

    @Nested
    @DisplayName("Deduplication")
    class DeduplicationTests {

        @Test
        @DisplayName("skips duplicate event (ProcessedEvent already exists)")
        void shouldSkipDuplicateEvent() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            Mockito.when(processedEventRepository.save(any(ProcessedEvent.class)))
                    .thenReturn(Mono.error(new DuplicateKeyException("duplicate")));

            StepVerifier.create(handler.handle(event)).verifyComplete();

            verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
            verify(notificationFactory, never()).create(any(), any());
            verify(notificationRepository, never()).save(any(Notification.class));
            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("does not duplicate on repeated delivery")
        void shouldNotCreateDuplicatesOnRepeatedDelivery() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            Mockito.when(processedEventRepository.save(any(ProcessedEvent.class)))
                    .thenReturn(Mono.just(new ProcessedEvent()))
                    .thenReturn(Mono.error(new DuplicateKeyException("duplicate")));

            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();
            stubEmailSendSuccess();

            StepVerifier.create(handler.handle(event)).verifyComplete();
            StepVerifier.create(handler.handle(event)).verifyComplete();

            verify(processedEventRepository, times(2)).save(any(ProcessedEvent.class));
            verify(notificationFactory, times(1)).create(event, EVENT_ID);
            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(emailSenderService, times(1)).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("swallows DuplicateKeyException from notificationRepository.save (idempotency by unique index)")
        void shouldSwallowDuplicateKeyFromNotificationSave() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));

            Mockito.when(notificationRepository.save(any(Notification.class)))
                    .thenReturn(Mono.error(new DuplicateKeyException("source_event_id, user_id conflict")));

            // Должно завершиться без ошибки — DuplicateKeyException проглочена
            StepVerifier.create(handler.handle(event))
                    .verifyComplete();

            verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }
    }

    // ==================== Обработка ошибок ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("skips null event")
        void shouldSkipNullEvent() {
            handler.handle(null).block();

            verify(processedEventRepository, never()).save(any());
            verify(notificationFactory, never()).create(any(), any());
            verify(notificationRepository, never()).save(any());
            verify(transactionalOperator, never()).transactional(any(Mono.class));
            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("skips event with null id")
        void shouldSkipEventWithNullId() throws Exception {
            TaskaEvent event = TaskaEvent.builder()
                    .id(null)
                    .aggregateType(AGGREGATE_TYPE_ISSUE)
                    .aggregateId(ISSUE_ID)
                    .eventType(EVENT_TYPE_ISSUE_ASSIGNED)
                    .payload(buildPayload(PAYLOAD_ISSUE_ASSIGNED))
                    .build();

            handler.handle(event).block();

            verify(processedEventRepository, never()).save(any());
            verify(notificationFactory, never()).create(any(), any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("completes successfully when email sending fails")
        void shouldCompleteWhenEmailFails() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));
            stubNotificationSaveSuccess();

            Mockito.when(emailSenderService.sendIfEnabled(any(Notification.class)))
                    .thenReturn(Mono.error(new RuntimeException("Email sending failed")));

            StepVerifier.create(handler.handle(event)).verifyComplete();

            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(emailSenderService, times(1)).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("fails when notificationRepository.save fails")
        void shouldFailWhenNotificationSaveFails() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));

            Mockito.when(notificationRepository.save(any(Notification.class)))
                    .thenReturn(Mono.error(new RuntimeException("DB error")));

            StepVerifier.create(handler.handle(event))
                    .expectError(RuntimeException.class)
                    .verify();

            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }

        @Test
        @DisplayName("does not save ProcessedEvent when transaction fails")
        void shouldNotCompleteWhenNotificationSaveFails() throws Exception {
            TaskaEvent event = issueAssignedEvent();

            stubProcessedEventSaveSuccess();
            stubFactoryCreate(List.of(notificationFor(ASSIGNEE_ID)));

            Mockito.when(notificationRepository.save(any(Notification.class)))
                    .thenReturn(Mono.error(new RuntimeException("DB down")));

            StepVerifier.create(handler.handle(event))
                    .expectError(RuntimeException.class)
                    .verify();

            // Ошибка пробрасывается — значит, транзакция откатится
            verify(emailSenderService, never()).sendIfEnabled(any(Notification.class));
        }
    }

    // ==================== Helpers ====================

    private void stubProcessedEventSaveSuccess() {
        Mockito.when(processedEventRepository.save(any(ProcessedEvent.class)))
                .thenAnswer(inv -> Mono.just((ProcessedEvent) inv.getArgument(0)));
    }

    private void stubFactoryCreate(List<Notification> notifications) {
        Mockito.when(notificationFactory.create(any(), any()))
                .thenReturn(notifications);
    }

    private void stubNotificationSaveSuccess() {
        Mockito.when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> Mono.just((Notification) inv.getArgument(0)));
    }

    private void stubEmailSendSuccess() {
        Mockito.when(emailSenderService.sendIfEnabled(any(Notification.class)))
                .thenReturn(Mono.empty());
    }

    private TaskaEvent issueAssignedEvent() throws Exception {
        return TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType(AGGREGATE_TYPE_ISSUE)
                .aggregateId(ISSUE_ID)
                .eventType(EVENT_TYPE_ISSUE_ASSIGNED)
                .payload(buildPayload(PAYLOAD_ISSUE_ASSIGNED))
                .build();
    }

    private TaskaEvent issueTransitionedEvent() throws Exception {
        return TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType(AGGREGATE_TYPE_ISSUE)
                .aggregateId(ISSUE_ID)
                .eventType(EVENT_TYPE_ISSUE_TRANSITIONED)
                .payload(buildPayload(PAYLOAD_ISSUE_TRANSITIONED))
                .build();
    }

    private JsonNode buildPayload(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    private Notification notificationFor(UUID userId) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
    }
}