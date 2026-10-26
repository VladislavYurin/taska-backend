package ru.taska.mcp;

import java.util.Set;

/** Only server-owned paths and query names may reach the fixed gateway origin. */
public enum GatewayEndpoint {
  ME("/users/me"),
  PROJECTS("/projects"),
  PROJECT_CONTEXT("/projects/{projectId}/context"),
  ISSUE_BY_KEY("/issues/by-key/{issueKey}"),
  ISSUE_DETAILS("/issues/{issueId}"),
  SEARCH_ISSUES(
      "/issues/search",
      "query",
      "projectId",
      "statusKey",
      "assigneeId",
      "reporterId",
      "priority",
      "issueType",
      "page",
      "pageSize"),
  COMMENTS("/projects/{projectId}/issues/{issueId}/comments", "page", "pageSize"),
  WORKLOGS("/projects/{projectId}/issues/{issueId}/worklogs", "page", "pageSize"),
  BOARD(
      "/projects/{projectId}/board",
      "issueType",
      "assigneeId",
      "labelId",
      "priority",
      "query",
      "includeDone",
      "pageSizePerColumn"),
  USERS("/users", "query", "page", "pageSize"),
  NOTIFICATIONS("/notifications", "pageSize", "offset", "unreadOnly");
  private final String path;
  private final Set<String> queries;

  GatewayEndpoint(String path, String... queries) {
    this.path = "/api/v1" + path;
    this.queries = Set.of(queries);
  }

  public String path() {
    return path;
  }

  public Set<String> queries() {
    return queries;
  }
}
