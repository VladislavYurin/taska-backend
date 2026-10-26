package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.api.issue.v1.WorklogResponse;
import ru.taska.domain.entity.Worklog;
import ru.taska.domain.aggregate.WorklogPage;

import java.time.Instant;

@Component
public class WorklogMapper {

    public AddIssueWorklogResponse toAddResponse(Worklog w) {
        return AddIssueWorklogResponse.newBuilder().setWorklog(toWorklogProto(w)).build();
    }

    public UpdateIssueWorklogResponse toUpdateResponse(Worklog w) {
        return UpdateIssueWorklogResponse.newBuilder().setWorklog(toWorklogProto(w)).build();
    }

    public ListIssueWorklogsResponse toListResponse(WorklogPage page) {
        return ListIssueWorklogsResponse.newBuilder()
                .setPage(page.page()).setPageSize(page.pageSize())
                .setTotalCount(page.totalCount()).setHasMore(page.hasMore())
                .addAllWorklogs(page.items().stream().map(worklog -> {
                    var builder = toWorklogProto(worklog).toBuilder();
                    if (page.authorProfiles().containsKey(worklog.getAuthorUserId())) {
                        builder.setAuthor(IssueMapperUtils.resolveUser(worklog.getAuthorUserId(), page.authorProfiles()));
                    }
                    return builder.build();
                }).toList()).build();
    }

    public DeleteIssueWorklogResponse toDeleteResponse(Worklog w) {
        return DeleteIssueWorklogResponse.newBuilder().setWorklog(toWorklogProto(w)).build();
    }

    public WorklogResponse toWorklogProto(Worklog worklog) {
        WorklogResponse.Builder builder = WorklogResponse.newBuilder()
                .setId(worklog.getId().toString())
                .setIssueId(worklog.getIssueId().toString())
                .setProjectId(worklog.getProjectId().toString())
                .setAuthorUserId(worklog.getAuthorUserId().toString())
                .setSpentMinutes(worklog.getSpentMinutes())
                .setWorkDate(worklog.getWorkDate().toString())
                .setCreatedAt(toTimestamp(worklog.getCreatedAt()))
                .setUpdatedAt(toTimestamp(worklog.getUpdatedAt()))
                .setVersion(worklog.getVersion());

        if (worklog.getComment() != null) {
            builder.setComment(worklog.getComment());
        }


        return builder.build();
    }

    private Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
