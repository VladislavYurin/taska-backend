package ru.taska.mcp;

import org.springframework.http.HttpMethod;

/** V1 writes only; method/path/accepted empty responses are server-owned. */
public enum GatewayMutation {
  CREATE_ISSUE(HttpMethod.POST, "/projects/{projectId}/issues", false),
  PATCH_ISSUE(HttpMethod.PATCH, "/issues/{issueId}", false),
  TRANSITION_ISSUE(HttpMethod.PUT, "/issues/{issueId}/transition/{transitionId}", false),
  COMMENT(HttpMethod.POST, "/projects/{projectId}/issues/{issueId}/comments", false),
  WORKLOG(HttpMethod.POST, "/projects/{projectId}/issues/{issueId}/worklogs", false),
  ADD_LABEL(HttpMethod.POST, "/projects/{projectId}/issues/{issueId}/labels", false),
  REMOVE_LABEL(HttpMethod.DELETE, "/projects/{projectId}/issues/{issueId}/labels/{labelId}", true),
  LINK_ISSUES(HttpMethod.POST, "/issues/{issueId}/links", false),
  WATCH(HttpMethod.PUT, "/projects/{projectId}/issues/{issueId}/watchers/me", false),
  UNWATCH(HttpMethod.DELETE, "/projects/{projectId}/issues/{issueId}/watchers/me", false),
  MARK_NOTIFICATION_READ(HttpMethod.PATCH, "/notifications/{notificationId}/read", false);
  private final HttpMethod method;
  private final String path;
  private final boolean empty;

  GatewayMutation(HttpMethod method, String path, boolean empty) {
    this.method = method;
    this.path = "/api/v1" + path;
    this.empty = empty;
  }

  public HttpMethod method() {
    return method;
  }

  public String path() {
    return path;
  }

  public boolean emptyResponse() {
    return empty;
  }
}
