package ru.taska.domain.aggregate;

import ru.taska.domain.AttachmentDto;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetail;
import ru.taska.domain.util.FetchResult;

import java.util.Map;
import java.util.UUID;
import java.util.List;

public record IssueDetailsAggregate(
        IssueCoreDetails issueCore,
        FetchResult<LabelResponses.ProjectLabelInfo> labels,
        FetchResult<IssueWatcher> watchers,
        FetchResult<AttachmentDto> attachments,
        FetchResult<IssueLinkDetail> links,
        List<IssueHistory> history,
        Map<UUID, UserSummary> userProfiles
) {
}
