package ru.taska.transport.grpc;

/**
 * Имена Micrometer-метрик gRPC-методов issue-service.
 * <p>
 * Значения должны быть compile-time константами для использования в {@code @TrackMetrics}.
 */
public final class IssueGrpcMetrics {

    private IssueGrpcMetrics() {
    }

    public static final String ADD_ISSUE_COMMENT_COUNTER = "issue-service_add-issue-comment_grpc_counter";
    public static final String ADD_ISSUE_COMMENT_TIMER = "issue-service_add-issue-comment_grpc_timer";

    public static final String UPDATE_ISSUE_COMMENT_COUNTER = "issue-service_update-issue-comment_grpc_counter";
    public static final String UPDATE_ISSUE_COMMENT_TIMER = "issue-service_update-issue-comment_grpc_timer";

    public static final String DELETE_ISSUE_COMMENT_COUNTER = "issue-service_delete-issue-comment_grpc_counter";
    public static final String DELETE_ISSUE_COMMENT_TIMER = "issue-service_delete-issue-comment_grpc_timer";

    public static final String LIST_ISSUE_COMMENTS_COUNTER = "issue-service_list-issue-comments_grpc_counter";
    public static final String LIST_ISSUE_COMMENTS_TIMER = "issue-service_list-issue-comments_grpc_timer";

    public static final String WATCH_ISSUE_COUNTER = "issue-service_watch-issue_grpc_counter";
    public static final String WATCH_ISSUE_TIMER = "issue-service_watch-issue_grpc_timer";

    public static final String UNWATCH_ISSUE_COUNTER = "issue-service_unwatch-issue_grpc_counter";
    public static final String UNWATCH_ISSUE_TIMER = "issue-service_unwatch-issue_grpc_timer";

    public static final String LIST_ISSUE_WATCHERS_COUNTER = "issue-service_list-issue-watchers_grpc_counter";
    public static final String LIST_ISSUE_WATCHERS_TIMER = "issue-service_list-issue-watchers_grpc_timer";

    public static final String GET_ISSUE_WATCH_STATE_COUNTER = "issue-service_get-issue-watch-state_grpc_counter";
    public static final String GET_ISSUE_WATCH_STATE_TIMER = "issue-service_get-issue-watch-state_grpc_timer";
}
