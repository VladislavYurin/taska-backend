package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;

import java.util.UUID;

/**
 * Сервис чтения inbox-а уведомлений пользователя (read-side).
 *
 * <p>Предоставляет операции для gRPC-слоя notification-service:
 * постраничную выдачу уведомлений пользователя и отметку уведомления
 * прочитанным.</p>
 *
 * <p>Сервис не создаёт уведомления — их создаёт
 * {@link NotificationEventHandler} на основе доменных событий Kafka.
 * Здесь выполняются только чтение и изменение статуса прочтения.</p>
 *
 * <p>Все операции ограничены рамками конкретного пользователя:
 * выборки и обновления идут по {@code userId}, чтобы пользователь
 * не мог получить или изменить чужие уведомления.</p>
 */
public interface NotificationInboxService {

    /**
     * Возвращает страницу уведомлений пользователя.
     *
     * <p>Если {@code unreadOnly = true}, возвращаются только уведомления
     * без {@code readAt}. Иначе возвращаются все уведомления пользователя.
     * Сортировка — от новых к старым ({@code created_at DESC}).</p>
     *
     * <p>Значения {@code pageSize} и {@code offset} нормализуются:
     * {@code pageSize <= 0} заменяется на значение по умолчанию,
     * слишком большое значение ограничивается максимумом,
     * отрицательный {@code offset} приводится к нулю.</p>
     *
     * @param userId     идентификатор пользователя-владельца уведомлений.
     * @param unreadOnly если {@code true} — вернуть только непрочитанные.
     * @param pageSize   желаемое количество уведомлений на страницу.
     * @param offset     смещение от начала списка.
     * @return {@link Flux} уведомлений пользователя (возможно пустой).
     */
    Flux<Notification> listNotifications(UUID userId, boolean unreadOnly, int pageSize, long offset);

    /**
     * Помечает уведомление пользователя как прочитанное.
     *
     * <p>Обновление выполняется по паре {@code (notificationId, userId)},
     * что гарантирует ownership: пользователь не может отметить чужое
     * уведомление. Если уведомление уже прочитано, возвращается текущее
     * состояние без изменения {@code readAt}.</p>
     *
     * @param notificationId идентификатор уведомления.
     * @param userId         идентификатор пользователя-владельца.
     * @return {@link Mono} с актуальным состоянием уведомления.
     * @throws ru.taska.exception.DomainException с {@code NOT_FOUND},
     *         если уведомление не найдено или не принадлежит пользователю.
     */
    Mono<Notification> markAsRead(UUID notificationId, UUID userId);
}