package ru.taska.service.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;

import java.util.UUID;

/**
 * Сервис-исполнитель для транзакционных операций со связями задачи.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueLinkExecutor {

    private final IssueLinkRepository issueLinkRepository;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    /**
     * Производит атомарное сохранение новой связи, истории задачи и outbox.
     *
     * @param requestId     идентификатор запроса
     * @param nodeId        идентификатор узла
     * @param projectId     идентификатор проекта
     * @param sourceIssueId идентификатор исходной задачи
     * @param targetIssueId идентификатор целевой задачи
     * @param linkType      тип устанавливаемой связи
     * @param actorUserId   идентификатор пользователя, устанавливающего связь
     * @return асинхронный контейнер <Mono{@link IssueLink}>, содержащий объект созданной связи
     */
    @Transactional
    public Mono<IssueLink> executeLinkCreation(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID sourceIssueId,
            UUID targetIssueId,
            IssueLinkType linkType,
            UUID actorUserId
    ) {
        var link = IssueLink.builder()
                .projectId(projectId)
                .sourceIssueId(sourceIssueId)
                .targetIssueId(targetIssueId)
                .linkType(linkType)
                .createdBy(actorUserId)
                .build();

        return issueLinkRepository.save(link)
                .onErrorMap(DuplicateKeyException.class,
                        ex -> new DomainException(DomainStatus.ALREADY_EXISTS, "Issue link already exists")
                )
                .flatMap(savedLink -> {
                    var payload = payloadSerializer.createIssueLinkCreatedPayload(sourceIssueId, targetIssueId, linkType, actorUserId);

                    return issueHistoryService.saveIssueHistory(
                                    requestId,
                                    nodeId,
                                    savedLink.getSourceIssueId(),
                                    actorUserId,
                                    IssueEventType.LINK_CREATED,
                                    payload
                            )
                            .then(issueHistoryService.saveIssueHistory(
                                    requestId,
                                    nodeId,
                                    savedLink.getTargetIssueId(),
                                    actorUserId,
                                    IssueEventType.LINK_CREATED,
                                    payload
                            ))
                            .then(outboxEventService.saveOutboxEvent(
                                    requestId,
                                    nodeId,
                                    AggregateType.ISSUE_LINK,
                                    savedLink.getId(),
                                    EventType.ISSUE_LINK_CREATED,
                                    payload
                            ))
                            .doOnSuccess(__ ->
                                    log.debug("[{}][{}] Link successfully created: source issue id {} -> target issue id {}, link type {}",
                                            requestId, nodeId, sourceIssueId, targetIssueId, linkType)
                            )
                            .thenReturn(savedLink);
                });
    }

    /**
     * Атомарно выполняет мягкое удаление связи, а так же сохраняет историю задачи и outbox.
     *
     * @param requestId   идентификатор запроса
     * @param nodeId      идентификатор узла
     * @param linkId      идентификатор связи
     * @param actorUserId идентификатор пользователя, удаляющего связь
     * @return асинхронный контейнер <Mono{@link IssueLink}>, содержащий объект созданной связи
     */
    @Transactional
    public Mono<IssueLink> executeLinkDeletion(
            String requestId,
            String nodeId,
            UUID linkId,
            UUID actorUserId
    ) {
        return issueLinkRepository.softDelete(linkId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue link not found or was already deleted: id={}", requestId, nodeId, linkId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue link not found or was already deleted"));
                }))
                .flatMap(deletedLink -> {
                    var payload = payloadSerializer.createIssueLinkDeletedPayload(
                            deletedLink.getSourceIssueId(),
                            deletedLink.getTargetIssueId(),
                            deletedLink.getLinkType(),
                            actorUserId
                    );

                    return issueHistoryService.saveIssueHistory(
                                    requestId,
                                    nodeId,
                                    deletedLink.getSourceIssueId(),
                                    actorUserId,
                                    IssueEventType.LINK_DELETED,
                                    payload
                            )
                            .then(issueHistoryService.saveIssueHistory(
                                    requestId,
                                    nodeId,
                                    deletedLink.getTargetIssueId(),
                                    actorUserId,
                                    IssueEventType.LINK_DELETED,
                                    payload
                            ))
                            .then(outboxEventService.saveOutboxEvent(
                                    requestId,
                                    nodeId,
                                    AggregateType.ISSUE_LINK,
                                    deletedLink.getId(),
                                    EventType.ISSUE_LINK_DELETED,
                                    payload
                            ))
                            .doOnSuccess(__ ->
                                    log.debug("[{}][{}] Link successfully deleted: id={}",
                                            requestId, nodeId, deletedLink.getId()
                                    ))
                            .thenReturn(deletedLink);
                });
    }

}