package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.GetIssueWatchStateResponse;
import ru.taska.api.issue.v1.IssueWatcherResponse;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.WatchIssueResponse;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.PageResult;
import ru.taska.domain.dto.IssueWatchStateDto;
import ru.taska.domain.dto.UnwatchIssueResult;
import ru.taska.domain.dto.WatchIssueResult;

import java.time.Instant;
import java.util.UUID;

@Component
public class IssueWatcherMapper {

    public IssueWatcherResponse toWatcherProto(IssueWatcher watcher) {
        return IssueWatcherResponse.newBuilder()
                .setId(watcher.getId().toString())
                .setIssueId(watcher.getIssueId().toString())
                .setProjectId(watcher.getProjectId().toString())
                .setUserId(watcher.getUserId().toString())
                .setCreatedAt(toTimestamp(watcher.getCreatedAt()))
                .setCreatedBy(watcher.getCreatedBy().toString())
                .build();
    }

    public WatchIssueResponse toWatchIssueResponse(WatchIssueResult result) {
        return WatchIssueResponse.newBuilder()
                .setWatcher(toWatcherProto(result.watcher()))
                .setWatchersCount((int) result.watchersCount())
                .build();
    }

    public UnwatchIssueResponse toUnwatchIssueResponse(UUID issueId, UnwatchIssueResult result) {
        return UnwatchIssueResponse.newBuilder()
                .setIssueId(issueId.toString())
                .setRemoved(result.removed())
                .setWatchersCount((int) result.watchersCount())
                .build();
    }

    public ListIssueWatchersResponse toListWatchersResponse(PageResult<IssueWatcher> page) {
        return ListIssueWatchersResponse.newBuilder()
                .addAllWatchers(page.items().stream().map(this::toWatcherProto).toList())
                .setTotalCount((int) page.totalCount())
                .build();
    }

    public GetIssueWatchStateResponse toWatchStateResponse(IssueWatchStateDto state) {
        return GetIssueWatchStateResponse.newBuilder()
                .setWatchedByMe(state.watchedByMe())
                .setWatchersCount((int) state.watchersCount())
                .build();
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
