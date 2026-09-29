package ru.taska.domain.aggregate;

import ru.taska.domain.AttachmentDto;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.util.FetchResult;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetail;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Промежуточный агрегат — все данные о задаче, ЗА ИСКЛЮЧЕНИЕМ профилей пользователей.
 * Существует как отдельный тип, потому что профили резолвятся вторым шагом,
 * на основе id, собранных из этих же данных.
 */
public record IssueDetailsSources(
        IssueCoreDetails issueCore,
        FetchResult<LabelResponses.ProjectLabelInfo> labels,
        FetchResult<IssueWatcher> watchers,
        FetchResult<AttachmentDto> attachments,
        FetchResult<IssueLinkDetail> links,
        List<IssueHistory> history
) {

    /**
     * Собирает все уникальные ID пользователей, упомянутых в задаче
     * (исполнитель, автор, наблюдатели, авторы вложений).
     */
    public Set<UUID> extractUserIds() {
        Set<UUID> userIds = new HashSet<>();

        if (issueCore != null && issueCore.issue() != null) {
            Issue issue = issueCore.issue();
            Optional.ofNullable(issue.getAssigneeId()).ifPresent(userIds::add);
            Optional.ofNullable(issue.getReporterId()).ifPresent(userIds::add);
        }

        if (watchers.available()) {
            watchers.items().stream()
                    .map(IssueWatcher::getUserId)
                    .filter(Objects::nonNull)
                    .forEach(userIds::add);
        }

        if (attachments.available()) {
            attachments.items().stream()
                    .map(AttachmentDto::issueAttachment)
                    .filter(Objects::nonNull)
                    .map(IssueAttachment::getUploadedBy)
                    .filter(Objects::nonNull)
                    .forEach(userIds::add);
        }

        return userIds;
    }

    /**
     * Достраивает финальный агрегат, добавляя резолвленные профили.
     */
    public IssueDetailsAggregate withProfiles(Map<UUID, UserSummary> profiles) {
        return new IssueDetailsAggregate(
                issueCore, labels, watchers, attachments, links, history, profiles
        );
    }
}
