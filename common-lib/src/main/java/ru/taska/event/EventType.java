package ru.taska.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * Типы событий, публикуемых через transactional outbox в Kafka.
 */
@Getter
@RequiredArgsConstructor
public enum EventType {
    ISSUE_CREATED("IssueCreated"),
    ISSUE_ASSIGNED("IssueAssigned"),
    ISSUE_TRANSITIONED("IssueTransitioned"),
    ISSUE_UPDATED("IssueUpdated"),
    ISSUE_DELETED("IssueDeleted"),
    ISSUE_LINK_CREATED("IssueLinkCreated"),
    ISSUE_LINK_DELETED("IssueLinkDeleted"),
    ATTACHMENT_ADDED("AttachmentAdded"),
    ATTACHMENT_DELETED("AttachmentDeleted"),
    USER_INVITED("UserInvited"),
    PROJECT_CREATED("ProjectCreated"),
    MEMBER_ADDED("MemberAdded"),
    MEMBER_REMOVED("MemberRemoved"),
    MEMBER_UPDATED("MemberUpdated"),
    USER_ACTIVATED("UserActivated"),
    UNSUPPORTED("Unsupported"),
    COMMENT_CREATED("CommentCreated"),
    COMMENT_UPDATED("CommentUpdated"),
    COMMENT_DELETED("CommentDeleted"),
    USER_BLOCKED("USER_BLOCKED"),
    USER_UNBLOCKED("USER_UNBLOCKED");

    private final String value;

    public static EventType fromValue(String value) {
        if (value == null) {
            return UNSUPPORTED;
        }

        var normalized = value.trim();

        return Arrays.stream(EventType.values())
                .filter(type -> type.getValue().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(UNSUPPORTED);
    }
}
