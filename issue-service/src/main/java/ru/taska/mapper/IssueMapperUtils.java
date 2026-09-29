package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import ru.taska.api.common.v1.UserSummaryResponse;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkViewType;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.projection.IssueLinkDetail;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class IssueMapperUtils {
    public static UserSummaryResponse resolveUser(UUID userId, Map<UUID, UserSummary> profiles) {
        if (userId == null)
            return null;
        UserSummary s = profiles.get(userId);
        var b = UserSummaryResponse.newBuilder()
                .setId(userId.toString())
                .setDisplayName(s != null ? s.displayName() : "Unknown user");
        if (s != null && s.avatarUrl() != null)
            b.setAvatarUrl(s.avatarUrl());
        return b.build();
    }

    public static IssueLinkViewType resolveViewType(IssueLinkDetail linkDetail, UUID issueId) {
        IssueLink link = linkDetail.link();
        var isSourceIssue = link.getSourceIssueId().equals(issueId);

        return switch (link.getLinkType()) {
            case RELATES_TO -> IssueLinkViewType.RELATES_TO;
            case BLOCKS -> isSourceIssue ? IssueLinkViewType.BLOCKS : IssueLinkViewType.IS_BLOCKED_BY;
            case DUPLICATES -> isSourceIssue ? IssueLinkViewType.DUPLICATES : IssueLinkViewType.IS_DUPLICATED_BY;
        };
    }

    public static ru.taska.api.issue.v1.IssueLinkViewType toProtoIssueLinkViewType(IssueLinkViewType domain) {
        return switch (domain) {
            case RELATES_TO -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_RELATES_TO;
            case BLOCKS -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_BLOCKS;
            case IS_BLOCKED_BY -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_IS_BLOCKED_BY;
            case DUPLICATES -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_DUPLICATES;
            case IS_DUPLICATED_BY -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_IS_DUPLICATED_BY;
        };
    }

    public static ru.taska.api.issue.v1.IssueLinkViewType resolveViewTypeToProto(IssueLinkDetail link, UUID issueId) {
        return toProtoIssueLinkViewType(resolveViewType(link, issueId));
    }

    public static Timestamp map(Instant instant) {
        return instant == null ? Timestamp.getDefaultInstance()
                : Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public static String map(JsonNode value) {
        return value == null ? null : value.toString();
    }

    public static String map(UUID id) {
        return id == null ? "" : id.toString();
    }

    public static String map(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    public static String map(Enum<?> e) {
        return e == null ? "" : e.name();
    }
}
