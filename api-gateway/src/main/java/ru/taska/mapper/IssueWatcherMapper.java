package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.IssueWatcherResponse;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.api.issue.v1.WatchIssueResponse;
import ru.taska.domain.dto.IssueWatcherResponseDto;
import ru.taska.domain.dto.ListIssueWatchersResponseDto;
import ru.taska.domain.dto.UnwatchIssueResponseDto;
import ru.taska.domain.dto.WatchIssueResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Маппер Proto Response → REST DTO для watchers.
 */
@Component
public class IssueWatcherMapper {

    public IssueWatcherResponseDto toRestWatcher(IssueWatcherResponse proto) {
        IssueWatcherResponseDto dto = new IssueWatcherResponseDto();
        dto.setId(UUID.fromString(proto.getId()));
        dto.setIssueId(UUID.fromString(proto.getIssueId()));
        dto.setProjectId(UUID.fromString(proto.getProjectId()));
        dto.setUserId(UUID.fromString(proto.getUserId()));
        dto.setCreatedAt(toOffsetDateTime(proto.getCreatedAt()));
        dto.setCreatedBy(UUID.fromString(proto.getCreatedBy()));
        return dto;
    }

    public ListIssueWatchersResponseDto toRestListWatchers(ListIssueWatchersResponse proto) {
        ListIssueWatchersResponseDto dto = new ListIssueWatchersResponseDto();

        dto.setWatchers(
                proto.getWatchersList().stream()
                        .map(this::toRestWatcher)
                        .collect(Collectors.toList())
        );
        dto.setTotalCount(proto.getTotalCount());
        return dto;
    }

    public WatchIssueResponseDto toRestWatchIssueResponse(WatchIssueResponse proto) {
        WatchIssueResponseDto dto = new WatchIssueResponseDto();
        if (proto.hasWatcher()) {
            dto.setWatcher(toRestWatcher(proto.getWatcher()));
        }
        dto.setWatchersCount(proto.getWatchersCount());
        return dto;
    }

    public UnwatchIssueResponseDto toRestUnwatchIssueResponse(UnwatchIssueResponse proto) {
        UnwatchIssueResponseDto dto = new UnwatchIssueResponseDto();
        dto.setIssueId(UUID.fromString(proto.getIssueId()));
        dto.setRemoved(proto.getRemoved());
        dto.setWatchersCount(proto.getWatchersCount());
        return dto;
    }

    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }

        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
