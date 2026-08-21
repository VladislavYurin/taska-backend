package ru.taska.transport.grpc;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Имена Micrometer-метрик gRPC-методов issue-service.
 * <p>
 * Значения должны быть compile-time константами для использования в {@code @TrackMetrics}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IssueGrpcMetrics {

    private static final String SERVICE_NAME = "issue-service_";

    public static final String ADD_ISSUE_COMMENT_COUNTER = SERVICE_NAME + "add-issue-comment_grpc_counter";
    public static final String ADD_ISSUE_COMMENT_TIMER = SERVICE_NAME + "add-issue-comment_grpc_timer";

    public static final String UPDATE_ISSUE_COMMENT_COUNTER = SERVICE_NAME + "update-issue-comment_grpc_counter";
    public static final String UPDATE_ISSUE_COMMENT_TIMER = SERVICE_NAME + "update-issue-comment_grpc_timer";

    public static final String DELETE_ISSUE_COMMENT_COUNTER = SERVICE_NAME + "delete-issue-comment_grpc_counter";
    public static final String DELETE_ISSUE_COMMENT_TIMER = SERVICE_NAME + "delete-issue-comment_grpc_timer";

    public static final String LIST_ISSUE_COMMENTS_COUNTER = SERVICE_NAME + "list-issue-comments_grpc_counter";
    public static final String LIST_ISSUE_COMMENTS_TIMER = SERVICE_NAME + "list-issue-comments_grpc_timer";

    public static final String WATCH_ISSUE_COUNTER = SERVICE_NAME + "watch-issue_grpc_counter";
    public static final String WATCH_ISSUE_TIMER = SERVICE_NAME + "watch-issue_grpc_timer";

    public static final String UNWATCH_ISSUE_COUNTER = SERVICE_NAME + "unwatch-issue_grpc_counter";
    public static final String UNWATCH_ISSUE_TIMER = SERVICE_NAME + "unwatch-issue_grpc_timer";

    public static final String LIST_ISSUE_WATCHERS_COUNTER = SERVICE_NAME + "list-issue-watchers_grpc_counter";
    public static final String LIST_ISSUE_WATCHERS_TIMER = SERVICE_NAME + "list-issue-watchers_grpc_timer";

    public static final String GET_ISSUE_WATCH_STATE_COUNTER = SERVICE_NAME + "get-issue-watch-state_grpc_counter";
    public static final String GET_ISSUE_WATCH_STATE_TIMER = SERVICE_NAME + "get-issue-watch-state_grpc_timer";
}
