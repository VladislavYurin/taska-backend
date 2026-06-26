package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.NotificationRepository;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional(readOnly = true)
    public Flux<Notification> listNotifications(UUID userId, boolean unreadOnly, int pageSize, long offset) {
        int normalizedPageSize = normalizePageSize(pageSize);
        long normalizedOffset = Math.max(offset, 0);

        if (unreadOnly) {
            return notificationRepository.findUnreadByUserId(userId, normalizedPageSize, normalizedOffset);
        }

        return notificationRepository.findAllByUserId(userId, normalizedPageSize, normalizedOffset);
    }

    @Override
    @Transactional
    public Mono<Notification> markAsRead(UUID notificationId, UUID userId) {
        return notificationRepository.markAsRead(notificationId, userId, Instant.now())
                .doOnNext(ignore ->
                        log.info("Notification markAsRead successfully: id={}", notificationId)
                )
                .switchIfEmpty(Mono.defer(() ->
                        notificationRepository.findByIdAndUserId(notificationId, userId)
                                .switchIfEmpty(Mono.error(new DomainException(
                                        DomainStatus.NOT_FOUND,
                                        "Notification not found"
                                )))
                                .doOnNext(ignore ->
                                        log.info("Notification already was read: id={}", notificationId)
                                )
                ))
                .doOnError(ex ->
                        log.error("Failed during invocation markAsRead for notification with: id={}, message={}", notificationId, ex.getMessage())
                );
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }

        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}