package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationListResult;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.NotificationRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    @org.springframework.beans.factory.annotation.Value("${taska.rpc.context.enabled:false}")
    private boolean trustedEnabled;
    private <T> Mono<T> scoped(UUID user,java.util.function.Function<UUID,Mono<T>> work){
      return ru.taska.security.tenant.VerifiedTenantContext.current().flatMap(context->{
        if(context.tenantId()==null || !user.toString().equals(context.actorId()))return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Access denied"));
        return work.apply(UUID.fromString(context.tenantId()));
      });
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<NotificationListResult> listNotifications(UUID userId, boolean unreadOnly, int pageSize, long offset) {
        if(trustedEnabled)return scoped(userId,tenant->Mono.zip(
            notificationRepository.findScoped(tenant,userId,unreadOnly,normalizePageSize(pageSize),Math.max(offset,0)).collectList(),
            notificationRepository.countByTenantIdAndUserIdAndReadAtIsNull(tenant,userId))
              .map(t->new NotificationListResult(t.getT1(),t.getT2())));
        int normalizedPageSize = normalizePageSize(pageSize);
        long normalizedOffset = Math.max(offset, 0);

        Mono<Long> unreadCountMono = notificationRepository.countByUserIdAndReadAtIsNull(userId);

        Mono<List<Notification>> notificationsMono = unreadOnly
                ? notificationRepository.findUnreadByUserId(userId, normalizedPageSize, normalizedOffset).collectList()
                : notificationRepository.findAllByUserId(userId, normalizedPageSize, normalizedOffset).collectList();

        return Mono.zip(notificationsMono, unreadCountMono)
                        .map(tuple -> new NotificationListResult(tuple.getT1(), tuple.getT2()));
    }

    @Override
    @Transactional
    public Mono<Notification> markAsRead(UUID notificationId, UUID userId) {
        if(trustedEnabled)return scoped(userId,tenant->notificationRepository.markScoped(tenant,notificationId,userId,Instant.now())
          .switchIfEmpty(notificationRepository.findByIdAndTenantIdAndUserId(notificationId,tenant,userId))
          .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Notification not found"))));
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

    @Override
    public Mono<Long> markAllAsRead(UUID userId) {
        if(trustedEnabled)return scoped(userId,tenant->notificationRepository.markAllScoped(tenant,userId,Instant.now()));
        return notificationRepository.markAllAsRead(userId, Instant.now())
                .doOnSuccess(markedCount ->
                        log.debug("Notifications markAllAsRead successfully: userId={}, markedCount={}", userId, markedCount)
                );
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }

        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}