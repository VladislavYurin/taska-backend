package ru.taska.domain.aggregate;

import ru.taska.domain.AttachmentDto;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetails;
import ru.taska.domain.util.FetchResult;

import java.util.Map;
import java.util.UUID;
import java.util.List;

/**
 * Полный набор данных, необходимый для формирования детальной информации о задаче.
 *
 * <p>Содержит основные данные задачи, связанные сущности, историю изменений
 * и профили пользователей, упомянутых в данных задачи.</p>
 *
 * @param issueCore основные данные задачи
 * @param labels метки задачи
 * @param watchers наблюдатели задачи
 * @param attachments вложения задачи
 * @param links связи задачи
 * @param history история изменений задачи
 * @param userProfiles профили пользователей, упомянутых в данных задачи
 */
public record IssueDetailsAggregate(
        IssueCoreDetails issueCore,
        FetchResult<LabelResponses.ProjectLabelInfo> labels,
        FetchResult<IssueWatcher> watchers,
        FetchResult<AttachmentDto> attachments,
        FetchResult<IssueLinkDetails> links,
        List<IssueHistory> history,
        Map<UUID, UserSummary> userProfiles
) {
}
