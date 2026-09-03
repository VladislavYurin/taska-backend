package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.OutboxRetryProperties;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.dto.AuditEventDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxRetryRepository;
import ru.taska.service.AuditService;
import ru.taska.service.OutboxRetryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Реализация административного retry outbox-событий.
 * <p>
 * Проверяет допустимость состояния outbox-события,
 * переводит событие обратно в NEW, читает состояние после изменения
 * и сохраняет запись аудита.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRetryServiceImpl implements OutboxRetryService {

    private static final String AUDIT_ACTION = "RETRY_OUTBOX_EVENT";
    private static final String TARGET_TABLE = "outbox_events";

    private final OutboxRetryRepository outboxRetryRepository;
    private final AuditService auditService;
    private final OutboxRetryProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Выполняет ручной retry FAILED или зависшего PROCESSING outbox-события.
     *
     * @param requestId   идентификатор запроса
     * @param nodeId      идентификатор узла
     * @param service     сервис-владелец outbox
     * @param eventId     идентификатор события
     * @param reason      причина ручного retry
     * @param actorUserId идентификатор администратора
     * @param actorLogin  логин администратора
     * @param actorRoles  роли администратора
     * @return snapshot события после изменения
     */
    @Override
    public Mono<OutboxEventSnapshot> retryOutboxEvent(
            String requestId,
            String nodeId,
            String service,
            UUID eventId,
            String reason,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles
    ) {
        return outboxRetryRepository.findById(service, eventId)
                .switchIfEmpty(Mono.defer(() ->
                        Mono.error(new DomainException(
                                DomainStatus.NOT_FOUND,
                                "Outbox event not found: " + eventId
                        ))
                ))
                .flatMap(oldSnapshot -> {
                    Instant stuckBefore = Instant.now()
                            .minus(properties.stuckThreshold());

                    validateRetryAllowed(oldSnapshot, stuckBefore);

                    return outboxRetryRepository.retry(
                                    service,
                                    eventId,
                                    stuckBefore
                            )
                            .flatMap(rowsUpdated -> {
                                if (rowsUpdated != 1L) {
                                    return Mono.error(new DomainException(
                                            DomainStatus.FAILED_PRECONDITION,
                                            "Outbox event is no longer eligible for retry"
                                    ));
                                }

                                return outboxRetryRepository.findById(
                                                service,
                                                eventId
                                        )
                                        .switchIfEmpty(Mono.defer(() ->
                                                Mono.error(new DomainException(
                                                        DomainStatus.INTERNAL,
                                                        "Outbox event disappeared after retry"
                                                ))
                                        ))
                                        .flatMap(newSnapshot ->
                                                completeRetry(
                                                        requestId,
                                                        service,
                                                        reason,
                                                        actorUserId,
                                                        actorLogin,
                                                        actorRoles,
                                                        oldSnapshot,
                                                        newSnapshot
                                                )
                                        );
                            });
                });
    }

    /**
     * Завершает retry проверкой неизменности payload
     * и записью административного аудита.
     */
    private Mono<OutboxEventSnapshot> completeRetry(
            String requestId,
            String service,
            String reason,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles,
            OutboxEventSnapshot oldSnapshot,
            OutboxEventSnapshot newSnapshot
    ) {
        if (!Objects.equals(
                oldSnapshot.payload(),
                newSnapshot.payload()
        )) {
            return Mono.error(new DomainException(
                    DomainStatus.INTERNAL,
                    "Outbox payload changed during retry"
            ));
        }

        AuditEventDto auditEvent = AuditEventDto.builder()
                .requestId(requestId)
                .actorUserId(actorUserId)
                .actorLogin(actorLogin)
                .actorRoles(actorRoles)
                .action(AUDIT_ACTION)
                .targetService(service)
                .targetTable(TARGET_TABLE)
                .targetId(oldSnapshot.id().toString())
                .oldValue(objectMapper.valueToTree(oldSnapshot))
                .newValue(objectMapper.valueToTree(newSnapshot))
                .reason(reason.trim())
                .build();

        return auditService.logAudit(auditEvent)
                .thenReturn(newSnapshot);
    }

    /**
     * Проверяет, что текущее состояние outbox-события допускает ручной retry.
     * <p>
     * Retry разрешён для события в статусе {@link OutboxStatus#FAILED}
     * или для зависшего события в статусе {@link OutboxStatus#PROCESSING}.
     *
     * @param snapshot    текущее состояние outbox-события
     * @param stuckBefore граница времени, до которой PROCESSING-событие
     *                    считается зависшим
     * @throws DomainException если событие не допускает ручной retry
     */
    private void validateRetryAllowed(
            OutboxEventSnapshot snapshot,
            Instant stuckBefore
    ) {
        if (snapshot.status() == OutboxStatus.FAILED) {
            return;
        }

        if (snapshot.status() == OutboxStatus.PROCESSING
                && snapshot.processingStartedAt() != null
                && snapshot.processingStartedAt().isBefore(stuckBefore)) {
            return;
        }

        throw new DomainException(
                DomainStatus.FAILED_PRECONDITION,
                "Outbox event with status "
                        + snapshot.status()
                        + " is not eligible for retry"
        );
    }
}
