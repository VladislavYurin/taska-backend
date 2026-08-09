package ru.taska.service.link;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueLinkExecutorTest {

    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID SOURCE_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    public static final IssueLinkType LINK_TYPE = IssueLinkType.RELATES_TO;

    @Mock
    private IssueLinkRepository issueLinkRepository;

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private PayloadSerializer payloadSerializer;

    @InjectMocks
    private IssueLinkExecutor executor;

    private IssueLink link;

    @BeforeEach
    void setUp() {
        link = IssueLink.builder()
                .id(LINK_ID)
                .projectId(PROJECT_ID)
                .sourceIssueId(SOURCE_ISSUE_ID)
                .targetIssueId(TARGET_ISSUE_ID)
                .linkType(LINK_TYPE)
                .createdBy(ACTOR_USER_ID)
                .build();
    }

    @Test
    @DisplayName("Должен успешно создать объект issueLink и сохранить все (issueLink, history, outbox) в БД")
    void executeLinkCreation_shouldSuccessfullyComplete() {
        var payload = new JsonNodeFactory().objectNode()
                .put("linkType", IssueLinkType.RELATES_TO.name());

        var history = IssueHistory.builder()
                .issueId(SOURCE_ISSUE_ID)
                .eventType(IssueEventType.LINK_CREATED)
                .build();

        var outboxEvent = OutboxEvent.builder()
                .aggregateId(SOURCE_ISSUE_ID)
                .eventType(EventType.ISSUE_LINK_CREATED.getValue())
                .build();

        Mockito.when(issueLinkRepository.save(Mockito.any(IssueLink.class)))
                .thenReturn(Mono.just(link));

        Mockito.when(payloadSerializer.createIssueLinkCreatedPayload(
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueLinkType.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(payload);

        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueEventType.class),
                        Mockito.any(JsonNode.class))
                )
                .thenReturn(Mono.just(history));

        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(AggregateType.class),
                        Mockito.any(UUID.class),
                        Mockito.any(EventType.class),
                        Mockito.any(JsonNode.class)
                ))
                .thenReturn(Mono.just(outboxEvent));

        StepVerifier.create(executor.executeLinkCreation(
                        REQUEST_ID,
                        NODE_ID,
                        PROJECT_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        LINK_TYPE,
                        ACTOR_USER_ID
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.getId()).isEqualTo(LINK_ID);
                    Assertions.assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
                    Assertions.assertThat(result.getSourceIssueId()).isEqualTo(SOURCE_ISSUE_ID);
                    Assertions.assertThat(result.getTargetIssueId()).isEqualTo(TARGET_ISSUE_ID);
                    Assertions.assertThat(result.getLinkType()).isEqualTo(LINK_TYPE);
                    Assertions.assertThat(result.getCreatedBy()).isEqualTo(ACTOR_USER_ID);
                })
                .verifyComplete();

        ArgumentCaptor<IssueLink> linkCaptor = ArgumentCaptor.forClass(IssueLink.class);

        Mockito.verify(issueLinkRepository).save(linkCaptor.capture());

        IssueLink argument = linkCaptor.getValue();

        Assertions.assertThat(argument.getId()).isNull();
        Assertions.assertThat(argument.getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(argument.getSourceIssueId()).isEqualTo(SOURCE_ISSUE_ID);
        Assertions.assertThat(argument.getTargetIssueId()).isEqualTo(TARGET_ISSUE_ID);
        Assertions.assertThat(argument.getLinkType()).isEqualTo(LINK_TYPE);
        Assertions.assertThat(argument.getCreatedBy()).isEqualTo(ACTOR_USER_ID);

        Mockito.verify(payloadSerializer)
                .createIssueLinkCreatedPayload(SOURCE_ISSUE_ID, TARGET_ISSUE_ID, LINK_TYPE, ACTOR_USER_ID);

        Mockito.verify(issueHistoryService)
                .saveIssueHistory(REQUEST_ID, NODE_ID, SOURCE_ISSUE_ID, ACTOR_USER_ID, IssueEventType.LINK_CREATED, payload);

        Mockito.verify(issueHistoryService)
                .saveIssueHistory(REQUEST_ID, NODE_ID, TARGET_ISSUE_ID, ACTOR_USER_ID, IssueEventType.LINK_CREATED, payload);

        Mockito.verify(outboxEventService)
                .saveOutboxEvent(REQUEST_ID, NODE_ID, AggregateType.ISSUE_LINK, LINK_ID, EventType.ISSUE_LINK_CREATED, payload);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом ALREADY_EXISTS при попытке сохранения дубликата связи")
    void executeLinkCreation_shouldThrowsException_whenLinkDuplicate() {
        Mockito.when(issueLinkRepository.save(Mockito.any(IssueLink.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("duplicate link")));

        StepVerifier.create(executor.executeLinkCreation(
                        REQUEST_ID,
                        NODE_ID,
                        PROJECT_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        LINK_TYPE,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.ALREADY_EXISTS);
                })
                .verify();

        Mockito.verify(issueLinkRepository).save(Mockito.any(IssueLink.class));
        Mockito.verifyNoMoreInteractions(payloadSerializer, issueHistoryService, outboxEventService);
    }

    @Test
    @DisplayName("Должен успешно произвести мягкое удаление объекта issueLink и сохранить в БД history и outbox")
    void executeLinkDeletion_shouldSuccessfullyComplete() {
        var payload = new JsonNodeFactory().objectNode()
                .put("linkType", IssueLinkType.RELATES_TO.name());

        var history = IssueHistory.builder()
                .issueId(SOURCE_ISSUE_ID)
                .eventType(IssueEventType.LINK_DELETED)
                .build();

        var outboxEvent = OutboxEvent.builder()
                .aggregateId(SOURCE_ISSUE_ID)
                .eventType(EventType.ISSUE_LINK_DELETED.getValue())
                .build();

        var deleted_at = Instant.parse("2007-01-01T01:00:00Z");
        var deletedLink = link.toBuilder()
                .deletedAt(deleted_at)
                .build();

        Mockito.when(issueLinkRepository.softDelete(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(deletedLink));

        Mockito.when(payloadSerializer.createIssueLinkDeletedPayload(
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueLinkType.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(payload);

        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueEventType.class),
                        Mockito.any(JsonNode.class))
                )
                .thenReturn(Mono.just(history));

        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(AggregateType.class),
                        Mockito.any(UUID.class),
                        Mockito.any(EventType.class),
                        Mockito.any(JsonNode.class)
                ))
                .thenReturn(Mono.just(outboxEvent));

        StepVerifier.create(executor.executeLinkDeletion(REQUEST_ID, NODE_ID, link.getId(), ACTOR_USER_ID))
                .assertNext(result -> {
                    Assertions.assertThat(result.getId()).isEqualTo(LINK_ID);
                    Assertions.assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
                    Assertions.assertThat(result.getSourceIssueId()).isEqualTo(SOURCE_ISSUE_ID);
                    Assertions.assertThat(result.getTargetIssueId()).isEqualTo(TARGET_ISSUE_ID);
                    Assertions.assertThat(result.getLinkType()).isEqualTo(LINK_TYPE);
                    Assertions.assertThat(result.getCreatedBy()).isEqualTo(ACTOR_USER_ID);
                    Assertions.assertThat(result.getDeletedAt()).isEqualTo(deleted_at);
                })
                .verifyComplete();

        Mockito.verify(issueLinkRepository).softDelete(link.getId());

        Mockito.verify(payloadSerializer)
                .createIssueLinkDeletedPayload(SOURCE_ISSUE_ID, TARGET_ISSUE_ID, LINK_TYPE, ACTOR_USER_ID);

        Mockito.verify(issueHistoryService)
                .saveIssueHistory(REQUEST_ID, NODE_ID, SOURCE_ISSUE_ID, ACTOR_USER_ID, IssueEventType.LINK_DELETED, payload);

        Mockito.verify(issueHistoryService)
                .saveIssueHistory(REQUEST_ID, NODE_ID, TARGET_ISSUE_ID, ACTOR_USER_ID, IssueEventType.LINK_DELETED, payload);

        Mockito.verify(outboxEventService)
                .saveOutboxEvent(REQUEST_ID, NODE_ID, AggregateType.ISSUE_LINK, LINK_ID, EventType.ISSUE_LINK_DELETED, payload);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом NOT_FOUND, если связь не была найдена")
    void executeLinkDeletion_shouldThrowsException_whenLinkNotFound() {
        Mockito.when(issueLinkRepository.softDelete(Mockito.any(UUID.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.executeLinkDeletion(REQUEST_ID, NODE_ID, link.getId(), ACTOR_USER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueLinkRepository).softDelete(link.getId());
        Mockito.verifyNoMoreInteractions(payloadSerializer, issueHistoryService, outboxEventService);
    }
}