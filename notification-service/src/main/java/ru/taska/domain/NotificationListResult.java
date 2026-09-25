package ru.taska.domain;

import java.util.List;

public record NotificationListResult(
        List<Notification> notifications,
        long unreadCount
) {
}
