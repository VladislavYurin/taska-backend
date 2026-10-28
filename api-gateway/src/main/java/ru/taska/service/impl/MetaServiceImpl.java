package ru.taska.service.impl;

import lombok.Getter;
import org.springframework.stereotype.Service;
import ru.taska.domain.dto.*;
import ru.taska.domain.ActionChannel;
import ru.taska.service.MetaService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Сервис словарей допустимых значений перечислений для {@code GET /api/v1/meta}.
 * <p>
 * Типы задач здесь — только подписи встроенных TASK/BUG/STORY. Разрешённые типы,
 * их подписи, цвета и значки нужно брать из реестра конкретного проекта.
 * Остальные коды берутся из OpenAPI DTO-перечислений.
 * <p>
 * Словари неизменны в пределах версии gateway, поэтому ответ и его ETag
 * вычисляются один раз при старте.
 */
@Service
@Getter
public class MetaServiceImpl implements MetaService {

    static final int ETAG_BYTES = 16;

    private final MetaResponseDto meta;
    private final String etag;

    public MetaServiceImpl() {
        this.meta = new MetaResponseDto(
                toPairList(new String[]{"TASK", "BUG", "STORY"}, code -> new IssueTypeMetaPairDto(code, issueTypeName(code))),
                toPairList(IssuePriorityDto.values(), code -> new IssuePriorityMetaPairDto(code, priorityName(code))),
                toPairList(IssueLinkTypeDto.values(), code -> new IssueLinkTypeMetaPairDto(code, linkTypeName(code))),
                toPairList(NotificationTypeDto.values(), code -> new NotificationTypeMetaPairDto(code, notificationTypeName(code))
                        .selectable(code != NotificationTypeDto.UNKNOWN)),
                toPairList(UserStatusDto.values(), code -> new UserStatusMetaPairDto(code, userStatusName(code))
                        .selectable(code != UserStatusDto.UNSPECIFIED))
        );
        this.meta.setActionChannels(toPairList(ActionChannel.values(),
                code -> new ActionChannelMetaPairDto(code.name(), actionChannelName(code))));
        this.meta.setLinkViewTypes(toPairList(IssueLinkViewTypeDto.values(),
                code -> new IssueLinkViewTypeMetaPairDto(code, linkViewTypeName(code))
                        .selectable(code != IssueLinkViewTypeDto.ISSUE_LINK_VIEW_TYPE_UNSPECIFIED
                                && code != IssueLinkViewTypeDto.UNRECOGNIZED)));
        this.etag = computeEtag(meta);
    }

    private static String actionChannelName(ActionChannel code) {
        return switch (code) {
            case UI -> "UI";
            case API_TOKEN -> "API";
            case MCP -> "MCP";
        };
    }

    private static String issueTypeName(String code) {
        return switch (code) {
            case "TASK" -> "Task";
            case "BUG" -> "Bug";
            case "STORY" -> "Story";
            default -> throw new IllegalArgumentException("Not a built-in issue type");
        };
    }

    private static String priorityName(IssuePriorityDto code) {
        return switch (code) {
            case LOW -> "Low";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
        };
    }

    private static String linkTypeName(IssueLinkTypeDto code) {
        return switch (code) {
            case BLOCKS -> "Blocks";
            case RELATES_TO -> "Relates to";
            case DUPLICATES -> "Duplicates";
        };
    }

    private static String linkViewTypeName(IssueLinkViewTypeDto code) {
        return switch (code) {
            case ISSUE_LINK_VIEW_TYPE_BLOCKS -> "Blocks";
            case ISSUE_LINK_VIEW_TYPE_IS_BLOCKED_BY -> "Is blocked by";
            case ISSUE_LINK_VIEW_TYPE_RELATES_TO -> "Relates to";
            case ISSUE_LINK_VIEW_TYPE_DUPLICATES -> "Duplicates";
            case ISSUE_LINK_VIEW_TYPE_IS_DUPLICATED_BY -> "Is duplicated by";
            case ISSUE_LINK_VIEW_TYPE_UNSPECIFIED -> "Unspecified";
            case UNRECOGNIZED -> "Unrecognized";
        };
    }

    private static String notificationTypeName(NotificationTypeDto code) {
        return switch (code) {
            case ISSUE_ASSIGNED -> "Issue assigned";
            case ISSUE_TRANSITIONED -> "Issue status changed";
            case ISSUE_CREATED -> "Issue created";
            case ISSUE_UPDATED -> "Issue updated";
            case ISSUE_DELETED -> "Issue deleted";
            case USER_INVITED -> "User invited";
            case USER_ACTIVATED -> "User activated";
            case PROJECT_CREATED -> "Project created";
            case PROJECT_ARCHIVED -> "Project archived";
            case MEMBER_ADDED -> "Member added";
            case MEMBER_UPDATED -> "Member role changed";
            case MEMBER_REMOVED -> "Member removed";
            case ISSUE_LINK_CREATED -> "Issue link created";
            case ISSUE_LINK_DELETED -> "Issue link deleted";
            case LABEL_ADDED -> "Label added";
            case LABEL_REMOVED -> "Label removed";
            case USER_BLOCKED -> "User blocked";
            case USER_UNBLOCKED -> "User unblocked";
            case PERSONAL_ACCESS_TOKEN_CREATED -> "Personal access token created";
            case PERSONAL_ACCESS_TOKEN_REVOKED -> "Personal access token revoked";
            case ISSUE_COMMENT_CREATED -> "Issue comment created";
            case ISSUE_COMMENT_UPDATED -> "Issue comment updated";
            case ISSUE_COMMENT_DELETED -> "Issue comment deleted";
            case ISSUE_ATTACHMENT_ADDED -> "Issue attachment added";
            case ISSUE_ATTACHMENT_DELETED -> "Issue attachment deleted";
            case UNKNOWN -> "Unknown";
        };
    }

    private static String userStatusName(UserStatusDto code) {
        return switch (code) {
            case UNSPECIFIED -> "Unspecified";
            case INVITED -> "Invited";
            case ACTIVE -> "Active";
            case BLOCKED -> "Blocked";
            case LOCKED -> "Locked";
        };
    }

    private static <Enum, Pair> List<Pair> toPairList(Enum[] codes, Function<Enum, Pair> toPair) {
        return Arrays.stream(codes).map(toPair).toList();
    }

    /**
     * ETag — префикс SHA-256 от {@code toString()} ответа: сгенерированные DTO
     * перечисляют в нём все поля, так что любое изменение кода или названия меняет ETag.
     */
    private static String computeEtag(MetaResponseDto meta) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(meta.toString().getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, ETAG_BYTES) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
