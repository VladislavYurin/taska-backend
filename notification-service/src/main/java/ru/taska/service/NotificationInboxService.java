package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationListResult;

import java.util.UUID;

public interface NotificationInboxService {

    Mono<NotificationListResult> listNotifications(UUID userId, boolean unreadOnly, int pageSize, long offset);

    Mono<Notification> markAsRead(UUID notificationId, UUID userId);

    Mono<Long> markAllAsRead(UUID userId);
}