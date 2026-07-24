package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.link.IssueLinkService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class IssueLinkServiceIT extends AbstractIT {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ANOTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String REQUEST_ID = "req-link-001";
    private static final String NODE_ID = "issue-service";

    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;

    @Autowired
    private IssueLinkService issueLinkService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueLinkRepository issueLinkRepository;

    @Autowired
    private IssueHistoryRepository issueHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanDb() {
        outboxEventRepository.deleteAll().block();
        issueHistoryRepository.deleteAll().block();
        issueLinkRepository.deleteAll().block();
        issueRepository.deleteAll().block();

        Mockito.when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()
                ))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Должен повторно создать связь после мягкого удаления")
    void shouldAllowRecreateLinkAfterSoftDelete() {
        var sourceIssue = createIssue(1);
        var targetIssue = createIssue(2);

        var createdLink = issueLinkService.createIssueLink(
                REQUEST_ID,
                NODE_ID,
                sourceIssue.getId(),
                targetIssue.getId(),
                IssueLinkType.BLOCKS,
                ACTOR_USER_ID
        ).block();

        Assertions.assertThat(createdLink).isNotNull();

        var deletedLink = issueLinkService.deleteIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        createdLink.getId(),
                        ACTOR_USER_ID
                )
                .block();

        Assertions.assertThat(deletedLink).isNotNull();
        Assertions.assertThat(deletedLink.getDeletedAt()).isNotNull();

        var recreatedLink = issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        targetIssue.getId(),
                        IssueLinkType.BLOCKS,
                        ACTOR_USER_ID
                )
                .block();

        Assertions.assertThat(recreatedLink).isNotNull();
        Assertions.assertThat(createdLink.getId()).isNotEqualTo(recreatedLink.getId());
        Assertions.assertThat(issueLinkRepository.findAllByIssueId(sourceIssue.getId()).count().block()).isEqualTo(1);
        Assertions.assertThat(issueHistoryRepository.count().block()).isEqualTo(6);
        Assertions.assertThat(outboxEventRepository.count().block()).isEqualTo(3);
    }

    @Test
    @DisplayName("Должен вернуть исключение DomainException со статусом NOT_FOUND, если исходная задача не существует")
    void createIssueLink_shouldFailWhenSourceIssueNotFound() {
        var targetIssue = createIssue(2);

        StepVerifier.create(issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        UUID.randomUUID(),
                        targetIssue.getId(),
                        IssueLinkType.BLOCKS,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Assertions.assertThat(issueLinkRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(issueHistoryRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(outboxEventRepository.findAll().collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("Должен вернуть исключение DomainException со статусом NOT_FOUND, если целевая задача не существует")
    void createIssueLink_shouldFailWhenTargetIssueNotFound() {
        var sourceIssue = createIssue(1);

        StepVerifier.create(issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        UUID.randomUUID(),
                        IssueLinkType.BLOCKS,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Assertions.assertThat(issueLinkRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(issueHistoryRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(outboxEventRepository.findAll().collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("Должен вернуть исключение DomainException со статусом INVALID_ARGUMENT, если задачи принадлежат разным проектам")
    void createIssueLink_shouldFailWhenIssuesBelongToDifferentProjects() {

        var sourceIssue = createIssue(PROJECT_ID, 1);
        var targetIssue = createIssue(ANOTHER_PROJECT_ID, 2);

        StepVerifier.create(issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        targetIssue.getId(),
                        IssueLinkType.BLOCKS,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error)
                            .isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                })
                .verify();

        Assertions.assertThat(issueLinkRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(issueHistoryRepository.findAll().collectList().block()).isEmpty();
        Assertions.assertThat(outboxEventRepository.findAll().collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("Связь RELATES_TO должна быть симметричной: A->B и B->A считаются дубликатом")
    void createIssueLink_relatesTo_shouldRejectReverseDuplicate() {
        var sourceIssue = createIssue(1);
        var targetIssue = createIssue(2);

        var createdLink = issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        targetIssue.getId(),
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                )
                .block();

        Assertions.assertThat(createdLink).isNotNull();
        Assertions.assertThat(createdLink.getSourceIssueId()).isEqualTo(sourceIssue.getId());
        Assertions.assertThat(createdLink.getTargetIssueId()).isEqualTo(targetIssue.getId());
        Assertions.assertThat(createdLink.getLinkType()).isEqualTo(IssueLinkType.RELATES_TO);

        StepVerifier.create(
                        issueLinkService.createIssueLink(
                                REQUEST_ID,
                                NODE_ID,
                                targetIssue.getId(),
                                sourceIssue.getId(),
                                IssueLinkType.RELATES_TO,
                                ACTOR_USER_ID
                        ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.ALREADY_EXISTS);
                })
                .verify();

        List<IssueLink> links = issueLinkRepository.findAll().collectList().block();

        Assertions.assertThat(links)
                .hasSize(1)
                .first()
                .extracting(
                        IssueLink::getSourceIssueId,
                        IssueLink::getTargetIssueId,
                        IssueLink::getLinkType
                )
                .containsExactly(
                        sourceIssue.getId(),
                        targetIssue.getId(),
                        IssueLinkType.RELATES_TO
                );
    }

    @Test
    @DisplayName("Должен мягко удалить связь и сохранить history + outbox")
    void deleteIssueLink_shouldSoftDeleteAndCreateHistoryAndOutbox() {
        var sourceIssue = createIssue(1);
        var targetIssue = createIssue(2);

        var createdLink = issueLinkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        targetIssue.getId(),
                        IssueLinkType.BLOCKS,
                        ACTOR_USER_ID
                )
                .block();

        Assertions.assertThat(createdLink).isNotNull();

        AtomicReference<IssueLink> deletedLinkRef = new AtomicReference<>();

        StepVerifier.create(issueLinkService.deleteIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        sourceIssue.getId(),
                        createdLink.getId(),
                        ACTOR_USER_ID
                ))
                .assertNext(deletedLink -> {
                    Assertions.assertThat(deletedLink).isNotNull();
                    Assertions.assertThat(deletedLink.getId()).isEqualTo(createdLink.getId());
                    Assertions.assertThat(deletedLink.getSourceIssueId()).isEqualTo(sourceIssue.getId());
                    Assertions.assertThat(deletedLink.getTargetIssueId()).isEqualTo(targetIssue.getId());
                    Assertions.assertThat(deletedLink.getLinkType()).isEqualTo(IssueLinkType.BLOCKS);
                    Assertions.assertThat(deletedLink.getDeletedAt()).isNotNull();

                    deletedLinkRef.set(deletedLink);
                })
                .verifyComplete();


        var deletedFromDb = issueLinkRepository.findById(createdLink.getId()).block();

        Assertions.assertThat(deletedFromDb).isNotNull();
        Assertions.assertThat(deletedFromDb.getDeletedAt()).isNotNull();

        List<IssueHistory> history = issueHistoryRepository.findAll()
                .collectList()
                .block();

        Assertions.assertThat(history).hasSize(4);

        Assertions.assertThat(history)
                .extracting(IssueHistory::getIssueId, IssueHistory::getEventType)
                .containsExactlyInAnyOrder(
                        Assertions.tuple(sourceIssue.getId(), IssueEventType.LINK_CREATED),
                        Assertions.tuple(targetIssue.getId(), IssueEventType.LINK_CREATED),
                        Assertions.tuple(sourceIssue.getId(), IssueEventType.LINK_DELETED),
                        Assertions.tuple(targetIssue.getId(), IssueEventType.LINK_DELETED)
                );

        List<OutboxEvent> events = outboxEventRepository.findAll()
                .collectList()
                .block();

        Assertions.assertThat(events).hasSize(2);

        Assertions.assertThat(events)
                .extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder(
                        EventType.ISSUE_LINK_CREATED.getValue(),
                        EventType.ISSUE_LINK_DELETED.getValue()
                );


        List<IssueLink> activeLinks = issueLinkRepository.findAllByIssueId(sourceIssue.getId())
                .collectList()
                .block();

        Assertions.assertThat(activeLinks).isEmpty();
    }

    private Issue createIssue(UUID projectId, int issueNumber) {
        var issue = Issue.builder()
                .projectId(projectId)
                .issueNumber(issueNumber)
                .issueKey("TEST-" + issueNumber)
                .issueType(IssueType.TASK)
                .summary("Issue " + issueNumber)
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(ACTOR_USER_ID)
                .version(1)
                .build();

        return issueRepository.save(issue).block();
    }

    private Issue createIssue(int issueNumber) {
        return createIssue(PROJECT_ID, issueNumber);
    }
}
