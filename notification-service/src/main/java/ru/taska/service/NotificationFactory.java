package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.domain.Notification;
import ru.taska.event.EventType;
import ru.taska.event.IssueInfo;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFactory {

    private final NotificationMapper notificationMapper;

    public List<Notification> create(TaskaEvent event, UUID eventId) {
        JsonNode payload = event.payload();
        EventType type = EventType.fromValue(event.eventType());

        log.debug("NotificationFactory.create: eventType={}, eventId={}, aggregateId={}",
                type, eventId, event.aggregateId());

        List<Notification> notifications = switch (type) {
            case ISSUE_CREATED -> buildIssueCreated(event, payload, eventId);
            case ISSUE_ASSIGNED -> buildIssueAssigned(event, payload, eventId);
            case ISSUE_TRANSITIONED -> buildIssueTransitioned(event, payload, eventId);
            case ISSUE_UPDATED -> buildIssueUpdated(event, payload, eventId);
            case ISSUE_DELETED -> buildIssueDeleted(event, payload, eventId);
            case ISSUE_LINK_CREATED -> buildIssueLinkCreated(event, payload, eventId);
            case ISSUE_LINK_DELETED -> buildIssueLinkDeleted(event, payload, eventId);
            case USER_INVITED -> buildUserInvited(event, eventId);
            case PROJECT_CREATED -> buildProjectCreated(event, payload, eventId);
            case MEMBER_ADDED -> buildMemberAdded(event, payload, eventId);
            case MEMBER_UPDATED -> buildMemberUpdated(event, payload, eventId);
            case MEMBER_REMOVED -> buildMemberRemoved(event, payload, eventId);
            case USER_ACTIVATED -> buildUserActivated(event, eventId);
            case ISSUE_LABEL_ADDED -> buildLabelAdded(event, payload, eventId);
            case ISSUE_LABEL_REMOVED -> buildLabelRemoved(event, payload, eventId);
            case USER_BLOCKED -> buildUserBlocked(event, payload, eventId);
            case USER_UNBLOCKED -> buildUserUnblocked(event, payload, eventId);
            case ISSUE_COMMENT_CREATED -> buildIssueCommentCreated(event, payload, eventId);
            default -> {
                log.info("Skip unsupported eventType={} eventId={}", event.eventType(), eventId);
                yield List.of();
            }
        };

        log.debug("NotificationFactory.create: created {} notifications for eventType={}, eventId={}",
                notifications.size(), type, eventId);

        return notifications;
    }

    private List<Notification> buildIssueAssigned(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID assigneeId = extractUuid(payload, "assigneeId");
        UUID actorUserId = extractUuid(payload, "actorUserId");
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        if (assigneeId == null) {
            log.warn("IssueAssigned event without assigneeId, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }
        Set<UUID> recipients = resolveRecipients(actorUserId, List.of(assigneeId), watcherIds);

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueAssigned(event, userId, issueInfo));
        }

        return notifications;
    }

    private List<Notification> buildIssueTransitioned(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID actorUserId = extractUuid(payload, "actorUserId");
        UUID assigneeId = extractUuid(payload, "assigneeId");
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        if (actorUserId == null && assigneeId == null && watcherIds.isEmpty()) {
            log.warn("IssueTransitioned event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        Set<UUID> recipients = resolveRecipients(
                actorUserId,
                assigneeId != null ? List.of(assigneeId) : List.of(),
                watcherIds
        );

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueTransitioned(event, userId, issueInfo));
        }
        return notifications;
    }

    private List<Notification> buildIssueCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID reporterId = extractUuid(payload, "reporterId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (reporterId == null && assigneeId == null) {
            log.warn("IssueCreated event without reporterId/assigneeId, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        List<Notification> notifications = new ArrayList<>();

        if (reporterId != null) {
            notifications.add(notificationMapper.toIssueCreated(event, reporterId, issueInfo));
        }

        if (assigneeId != null && !assigneeId.equals(reporterId) ) {
            notifications.add(notificationMapper.toIssueCreated(event, assigneeId, issueInfo));
        }

        return notifications;
    }

    private List<Notification> buildIssueUpdated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID actorUserId = extractUuid(payload, "actorUserId");
        UUID assigneeId = extractUuid(payload, "assigneeId");
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        if (actorUserId == null && assigneeId == null && watcherIds.isEmpty()) {
            log.warn("IssueUpdated event without recipients, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }
        Set<UUID> recipients = resolveRecipients(
                actorUserId,
                assigneeId != null ? List.of(assigneeId) : List.of(),
                watcherIds
        );

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueUpdated(event, userId, issueInfo));
        }
        return notifications;
    }

    private List<Notification> buildIssueDeleted(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID actorUserId = extractUuid(payload, "actorUserId");
        UUID assigneeId = extractUuid(payload, "assigneeId");

        if (actorUserId == null && assigneeId == null) {
            log.warn("IssueDeleted event without recipients, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        if (assigneeId != null && !assigneeId.equals(actorUserId)) {
            recipients.add(assigneeId);
        }

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueDeleted(event, userId, issueInfo));
        }
        return notifications;
    }

    private List<Notification> buildIssueLinkCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID createdBy  = extractUuid(payload, "createdBy");
        UUID targetIssueId = extractUuid(payload, "targetIssueId");
        String linkType = extractString(payload, "linkType");
        UUID linkId = event.aggregateId();
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        Set<UUID> recipients = new LinkedHashSet<>(watcherIds);

        if (createdBy != null) {
            recipients.remove(createdBy);
        }

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueLinkCreated(
                    event, userId, issueInfo, targetIssueId, linkType, linkId
            ));
        }
        return notifications;
    }

    private List<Notification> buildIssueLinkDeleted(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID deletedBy  = extractUuid(payload, "deletedBy");
        UUID targetIssueId = extractUuid(payload, "targetIssueId");
        String linkType = extractString(payload, "linkType");
        UUID linkId = event.aggregateId();
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        Set<UUID> recipients = new LinkedHashSet<>(watcherIds);

        if (deletedBy != null) {
            recipients.remove(deletedBy);
        }

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toIssueLinkDeleted(
                    event, userId, issueInfo, targetIssueId, linkType, linkId
            ));
        }
        return notifications;
    }

    private List<Notification> buildIssueCommentCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID authorUserId = extractUuid(payload, "authorUserId");
        String body = extractString(payload, "body");
        List<UUID> watcherIds = extractUuidList(payload, "watcherIds");

        if (authorUserId == null) {
            log.warn("CommentCreated event without authorUserId, eventId={}", eventId);
            return List.of();
        }

        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        Set<UUID> recipients = new LinkedHashSet<>(watcherIds);
        recipients.remove(authorUserId);

        List<Notification> notifications = new ArrayList<>(recipients.size());
        for (UUID userId : recipients) {
            notifications.add(notificationMapper.toCommentCreated(event, userId, issueInfo, body));
        }
        return notifications;
    }

    private List<Notification> buildUserInvited(TaskaEvent event, UUID eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            log.warn("UserInvited event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toUserInvited(event));
    }

    private List<Notification> buildProjectCreated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID createdBy = extractUuid(payload, "createdBy");
        if (createdBy == null) {
            log.warn("ProjectCreated event without createdBy, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toProjectCreated(event, createdBy));
    }

    private List<Notification> buildMemberAdded(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberAdded event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberAdded(event, userId));
    }

    private List<Notification> buildMemberUpdated(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberUpdated event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberUpdated(event, userId));
    }

    private List<Notification> buildMemberRemoved(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        if (userId == null) {
            log.warn("MemberRemoved event without userId, eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toMemberRemoved(event, userId));
    }

    private List<Notification> buildUserActivated(TaskaEvent event, UUID eventId) {
        UUID userId = event.aggregateId();
        if (userId == null) {
            log.warn("UserActivated event without aggregateId (user id), eventId={}", eventId);
            return List.of();
        }
        return List.of(notificationMapper.toUserActivated(event));
    }

    private UUID extractUuid(JsonNode payload, String fieldName) {
        if (payload == null || !payload.hasNonNull(fieldName)) {
            return null;
        }
        try {
            return UUID.fromString(payload.get(fieldName).asText());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID in payload field={} value={}", fieldName, payload.get(fieldName), e);
            return null;
        }
    }

    private String extractString(JsonNode payload, String fieldName) {
        if (payload == null || !payload.hasNonNull(fieldName)) {
            return null;
        }
        try {
            return payload.get(fieldName).asText();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid String in payload field={} value={}", fieldName, payload.get(fieldName), e);
            return null;
        }
    }

    /**
     * Извлекает из payload события информацию о задаче.
     */
    private IssueInfo extractIssueInfo(JsonNode payload) {
        UUID issueId = extractUuid(payload, "issueId");
        String issueKey = extractString(payload, "issueKey");
        UUID projectId = extractUuid(payload, "projectId");
        return new IssueInfo(issueId, issueKey, projectId);
    }

    private List<Notification> buildLabelAdded(TaskaEvent event, JsonNode payload, UUID eventId) {

        UUID addedBy = extractUuid(payload, "createdBy");
        String labelName = extractString(payload, "labelName");
        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        if (addedBy == null || labelName == null || issueInfo.issueId() == null) {
            log.warn("LabelAdded event missing required fields, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toLabelAdded(event, addedBy, issueInfo, labelName));
    }

    private List<Notification> buildLabelRemoved(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID removedBy = extractUuid(payload, "deletedBy");
        String labelName = extractString(payload, "labelName");
        IssueInfo issueInfo = extractIssueInfo(payload);
        if (skipIfNoIssueInfo(issueInfo, event, eventId)) {
            return List.of();
        }

        if (removedBy == null || labelName == null || issueInfo.issueId() == null) {
            log.warn("LabelRemoved event missing required fields, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toLabelRemoved(event, removedBy, issueInfo, labelName));
    }

    /**
     * Создает уведомление для пользователя о его блокировке
     */
    private List<Notification> buildUserBlocked(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        String reason = extractString(payload, "reason");

        if (userId == null) {
            log.warn("UserBlocked event without userId, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toUserBlocked(event, userId, reason));
    }

    /**
     * Создает уведомление для пользователя о его разблокировке
     */
    private List<Notification> buildUserUnblocked(TaskaEvent event, JsonNode payload, UUID eventId) {
        UUID userId = extractUuid(payload, "userId");
        String reason = extractString(payload, "reason");

        if (userId == null) {
            log.warn("UserUnblocked event without userId, eventId={}", eventId);
            return List.of();
        }

        return List.of(notificationMapper.toUserUnblocked(event, userId, reason));
    }

    /**
     * Собирает уникальный набор получателей уведомления из двух источников:
     * <ol>
     *   <li><b>Явные получатели</b> ({@code explicit}) — например, assignee, reporter,
     *       участники, указанные в payload события.</li>
     *   <li><b>Watchers задачи</b> ({@code watcherIds}) — подписчики, полученные из
     *       snapshot в payload.</li>
     * </ol>
     *
     * <p>Правила обработки:</p>
     * <ul>
     *   <li><b>Actor исключается</b> из обоих источников — пользователь,
     *       совершивший действие, не получает уведомление о нём.
     *   </li>
     *   <li><b>Дубликаты не создаются</b> — если один и тот же пользователь
     *       присутствует и в {@code explicit}, и в {@code watcherIds},
     *       он попадёт в результат ровно один раз.
 *       </li>
     *   <li><b>Порядок сохраняется</b> — благодаря {@link LinkedHashSet}
     *       явные получатели идут первыми, затем watchers в порядке их появления.</li>
     * </ul>
     *
     * <p><b>Null-safety:</b> метод безопасно обрабатывает {@code null} в любом из
     * аргументов:</p>
     * <ul>
     *   <li>{@code actorUserId == null} — actor не исключается ни из одного источника;</li>
     *   <li>{@code explicit == null} или содержит {@code null} — такие элементы пропускаются;</li>
     *   <li>{@code watcherIds == null} или содержит {@code null} — такие элементы пропускаются.</li>
     * </ul>
     *
     * @param actorUserId ID пользователя, совершившего действие.
     *                    Исключается из результата. Может быть {@code null}.
     * @param explicit    явные получатели из payload события (например, assignee).
     *                    Может быть {@code null} или пустым.
     * @param watcherIds  ID watchers задачи из snapshot payload.
     *                    Может быть {@code null} или пустым.
     * @return уникальный набор получателей без actor'а и без дубликатов.
     *         Никогда не {@code null} — при отсутствии получателей вернётся пустой {@link Set}.
     */
    private Set<UUID> resolveRecipients(UUID actorUserId, Collection<UUID> explicit, List<UUID> watcherIds) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (explicit != null) {
            for (UUID id : explicit) {
                if (id != null && !id.equals(actorUserId)){
                    recipients.add(id);
                }
            }
        }
        if (watcherIds != null) {
            for (UUID id : watcherIds) {
                if (id != null && !id.equals(actorUserId)) {
                    recipients.add(id);
                }
            }
        }

        return recipients;
    }

    private List<UUID> extractUuidList(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field)) return List.of();
        JsonNode array = payload.get(field);
        if (!array.isArray()) return List.of();
        List<UUID> result = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            try {
                result.add(UUID.fromString(item.asString()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid UUID in list field={} value={}", field, item.asString());
            }
        }
        return result;
    }

    /**
     * Проверяет, что payload содержит issue-информацию.
     * Если нет — логирует и возвращает true (сигнал «скипнуть создание уведомления»).
     */
    private boolean skipIfNoIssueInfo(IssueInfo issueInfo, TaskaEvent event, UUID eventId) {
        if (!issueInfo.hasAnyField()) {
            log.warn("Event without issue info, skipping. eventType={}, eventId={}, aggregateId={}",
                    event.eventType(), eventId, event.aggregateId());
            return true;
        }
        return false;
    }
}
