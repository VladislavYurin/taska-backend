package ru.taska.domain.aggregate;

import ru.taska.domain.dto.AttachmentDto;
import ru.taska.domain.entity.IssueHistory;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;
import ru.taska.domain.entity.IssueWatcher;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetails;
import ru.taska.domain.util.FetchResult;

import java.util.Map;
import java.util.UUID;
import java.util.List;

/**
 * Агрегированный набор данных для формирования детальной информации о задаче.
 *
 * <p>Содержит основные данные задачи, связанные сущности, историю изменений
 * и профили пользователей, упомянутых в данных задачи.</p>
 *
 * @param sources основные и сопутствующие данные задачи
 * @param userProfiles профили пользователей, упомянутых в данных задачи
 */
public record IssueDetailsAggregate(
        IssueDetailsSources sources,
        Map<UUID, UserSummary> userProfiles
) {
    public IssueCoreDetails issueCore() {
        return sources.issueCore();
    }

    public FetchResult<LabelResponses.ProjectLabelInfo> labels() {
        return sources.labels();
    }

    public FetchResult<IssueWatcher> watchers() {
        return sources.watchers();
    }

    public FetchResult<AttachmentDto> attachments() {
        return sources.attachments();
    }

    public FetchResult<IssueLinkDetails> links() {
        return sources.links();
    }

    public List<IssueHistory> history() {
        return sources.history();
    }
}
