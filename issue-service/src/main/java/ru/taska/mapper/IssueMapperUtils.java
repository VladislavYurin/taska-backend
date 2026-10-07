package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import ru.taska.api.common.v1.UserSummaryResponse;
import ru.taska.domain.entity.IssueLink;
import ru.taska.domain.aggregate.IssueLinkViewType;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.projection.IssueLinkDetails;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** * Утилиты для преобразования доменных моделей задач в protobuf-модели. */
@Slf4j
public final class IssueMapperUtils {
    public static UserSummaryResponse resolveUser(UUID userId, Map<UUID, UserSummary> profiles) {
        if (userId == null) {
            return null;
        }

        UserSummary profile = profiles.get(userId);

        UserSummaryResponse.Builder builder = UserSummaryResponse.newBuilder()
                .setId(userId.toString());
        if (profile == null) {
            log.warn("User profile not found: userId={}", userId);
            return builder.build();
        }

        builder.setDisplayName(profile.displayName());

        if (profile.avatarUrl() != null) {
            builder.setAvatarUrl(profile.avatarUrl());
        }

        return builder.build();
    }

    public static IssueLinkViewType resolveViewType(IssueLinkDetails linkDetail, UUID issueId) {
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

    /**
     * Определяет тип отображения связи относительно текущей задачи
     * и преобразует его в protobuf-тип.
     * <p>Метод используется при маппинге {@link IssueLinkDetails} в
     * {@code IssueLinkResponse} для заполнения поля {@code viewLinkType}.</p>
     *
     * @param link детали связи между задачами
     * @param issueId идентификатор текущей задачи
     * @return protobuf-тип отображения связи относительно текущей задачи
     */
    public static ru.taska.api.issue.v1.IssueLinkViewType resolveViewTypeToProto(IssueLinkDetails link, UUID issueId) {
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
