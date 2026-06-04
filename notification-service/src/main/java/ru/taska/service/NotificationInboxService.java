package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;

import java.util.UUID;

public interface NotificationInboxService {

    Flux<Notification> listNotifications(UUID userId, boolean unreadOnly, int pageSize, long offset);

    Mono<Notification> markAsRead(UUID notificationId, UUID userId);
}