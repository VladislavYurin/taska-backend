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
    USER_INVITED("UserInvited"),
    PROJECT_CREATED("ProjectCreated"),
    MEMBER_ADDED("MemberAdded"),
    MEMBER_REMOVED("MemberRemoved"),
    USER_ACTIVATED("UserActivated"),
    UNSUPPORTED("Unsupported");

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
