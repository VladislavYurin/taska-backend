package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.api.issue.v1.WorklogResponse;
import ru.taska.domain.Worklog;

import java.time.Instant;
import java.util.List;

@Component
public class WorklogMapper {

    public AddIssueWorklogResponse toAddResponse(Worklog w) {
        return AddIssueWorklogResponse.newBuilder().setWorklog(toWorklogProto(w)).build();
    }

    public UpdateIssueWorklogResponse toUpdateResponse(Worklog w) {
        return UpdateIssueWorklogResponse.newBuilder().setWorklog(toWorklogProto(w)).build();
    }

    public ListIssueWorklogsResponse toListResponse(List<Worklog> w) {
        return ListIssueWorklogsResponse.newBuilder().addAllWorklogs(w.stream()
                .map(this::toWorklogProto).toList()).build();
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
