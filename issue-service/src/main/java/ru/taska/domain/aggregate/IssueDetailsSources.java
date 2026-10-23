package ru.taska.domain.aggregate;

import ru.taska.domain.dto.AttachmentDto;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueAttachment;
import ru.taska.domain.entity.IssueHistory;
import ru.taska.domain.entity.IssueWatcher;
import ru.taska.domain.util.FetchResult;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetails;
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
        FetchResult<IssueLinkDetails> links,
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
}
