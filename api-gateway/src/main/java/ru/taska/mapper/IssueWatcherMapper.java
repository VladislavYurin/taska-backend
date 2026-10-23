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

import java.net.URI;
import java.util.List;
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
        dto.setCreatedAt(MappingUtils.toOffsetDateTime(proto.getCreatedAt()));
        dto.setCreatedBy(UUID.fromString(proto.getCreatedBy()));
        if (proto.hasDisplayName()) {
            dto.displayName(proto.getDisplayName());
        }
        if (proto.hasAvatarUrl()) {
            dto.avatarUrl(URI.create(proto.getAvatarUrl()));
        }
        return dto;
    }

    public ListIssueWatchersResponseDto toRestListWatchers(ListIssueWatchersResponse proto) {
        ListIssueWatchersResponseDto dto = new ListIssueWatchersResponseDto();

        dto.setWatchers(toRestIssueWatcherResponseList(proto));
        dto.setTotalCount(proto.getTotalCount());
        return dto;
    }

    public List<IssueWatcherResponseDto> toRestIssueWatcherResponseList(ListIssueWatchersResponse responseList) {
        return responseList.getWatchersList().stream()
                .map(this::toRestWatcher)
                .collect(Collectors.toList());
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
}
