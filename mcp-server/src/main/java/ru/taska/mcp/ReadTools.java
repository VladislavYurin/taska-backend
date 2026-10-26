package ru.taska.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ReadTools implements ToolContributor {
  private final GatewayClient gateway;
  private final ToolSchemas schemas;
  private final JsonMapper mapper;

  public ReadTools(GatewayClient gateway, ToolSchemas schemas, JsonMapper mapper) {
    this.gateway = gateway;
    this.schemas = schemas;
    this.mapper = mapper;
  }

  @Override
  public List<ToolRegistration> tools() {
    return List.of(
        tool(
            "whoami",
            "Текущий пользователь Taska, тип токена, scopes и срок действия.",
            this::whoami),
        tool(
            "list_projects",
            "Доступные проекты пользователя. Страница стабильного списка проектов.",
            this::listProjects),
        tool(
            "get_project",
            "Контекст проекта по ключу: участники, типы, метки, workflows со статусами и"
                + " переходами.",
            this::getProject),
        tool(
            "search_issues",
            "Поиск задач. Для «мои задачи в работе в TAS» задайте project_key=TAS, assignee=me и"
                + " при необходимости status. Без status поиск не ограничивает состояния.",
            this::searchIssues),
        tool(
            "get_issue",
            "Карточка задачи по ключу с авторами, метками, связями, наблюдателями и последними 20"
                + " событиями истории. Описание читается окнами text_offset/text_limit.",
            this::getIssue),
        tool(
            "list_comments",
            "Страница комментариев задачи; длинные тексты читаются окнами text_offset/text_limit,"
                + " продолжение списка через page.",
            this::listComments),
        tool(
            "list_worklogs",
            "Страница трудозатрат задачи с авторами, включая бывших участников; текст comment"
                + " читается окнами.",
            this::listWorklogs),
        tool(
            "get_board",
            "Первая страница каждой колонки доски проекта. Учитывает workflow собственного типа;"
                + " продолжение задач — search_issues по статусу.",
            this::getBoard),
        tool(
            "search_users",
            "Поиск доступных пользователей по имени/email от трёх символов; при неоднозначности"
                + " используйте точный UUID.",
            this::searchUsers),
        tool(
            "list_notifications",
            "Страница собственных уведомлений, фильтр unread_only; unreadCount относится ко всем"
                + " уведомлениям пользователя.",
            this::listNotifications));
  }

  private ToolRegistration tool(
      String name, String description, Function<ToolInvocation, Mono<ToolData>> handler) {
    return new ToolRegistration(
        schemas.definition("/schemas/read-tools.json", name, description, "read", true, true),
        handler);
  }

  private Mono<ToolData> whoami(ToolInvocation call) {
    return gateway
        .currentUser(call.context())
        .map(
            user -> {
              var expiry = call.context().user().getTokenExpiresAt();
              JsonNode data =
                  mapper.valueToTree(
                      Map.of(
                          "user",
                          user,
                          "token_kind",
                          "PAT",
                          "scopes",
                          call.context().user().getScopesList(),
                          "expires_at",
                          Instant.ofEpochSecond(expiry.getSeconds(), expiry.getNanos())
                              .toString()));
              return new ToolData(
                  data,
                  "пользователь получен; token_kind=PAT, scopes="
                      + String.join(",", call.context().user().getScopesList()));
            });
  }

  private Mono<ToolData> listProjects(ToolInvocation call) {
    return call.resolver()
        .projects()
        .map(
            body -> {
              JsonNode items = ToolResolver.requiredArray(body, "items");
              int page = call.arguments().integer("page", 0),
                  size = call.arguments().integer("page_size", 20);
              long offset = (long) page * size;
              ArrayNode selected = mapper.createArrayNode();
              for (long i = offset; i < items.size() && i < offset + size; i++)
                selected.add(items.get((int) i));
              boolean more = offset + selected.size() < items.size();
              return new ToolData(
                  mapper.valueToTree(
                      Map.of(
                          "items",
                          selected,
                          "page",
                          page,
                          "total_count",
                          items.size(),
                          "has_more",
                          more)),
                  pageSummary(selected.size(), page, more));
            });
  }

  private Mono<ToolData> getProject(ToolInvocation call) {
    return call.resolver()
        .project(call.arguments().key("project_key"))
        .flatMap(
            project ->
                call.resolver()
                    .projectContext(project)
                    .map(
                        data ->
                            new ToolData(
                                data,
                                project.context(),
                                "проект " + project.key() + ", контекст получен")));
  }

  private Mono<ToolData> searchIssues(ToolInvocation call) {
    Map<String, Object> query = page(call.arguments());
    copy(
        call.arguments(),
        query,
        "query",
        "query",
        "status",
        "statusKey",
        "priority",
        "priority",
        "issue_type",
        "issueType");
    Mono<Map<String, Object>> project =
        call.arguments().has("project_key")
            ? call.resolver()
                .project(call.arguments().key("project_key"))
                .map(
                    value -> {
                      query.put("projectId", value.id());
                      return value.context();
                    })
            : Mono.just(Map.of());
    return project.flatMap(
        metadata ->
            users(call, query)
                .then(
                    Mono.defer(
                        () ->
                            gateway.get(
                                GatewayEndpoint.SEARCH_ISSUES, call.context(), Map.of(), query)))
                .map(
                    data ->
                        new ToolData(data, metadata, listSummary(data, call.arguments(), false))));
  }

  private Mono<ToolData> getIssue(ToolInvocation call) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue ->
                gateway
                    .get(
                        GatewayEndpoint.ISSUE_DETAILS,
                        call.context(),
                        Map.of("issueId", issue.id()),
                        Map.of())
                    .map(
                        data -> {
                          ObjectNode copy = (ObjectNode) data.deepCopy();
                          JsonNode history = ToolResolver.requiredArray(copy, "history");
                          boolean truncated = history.size() > 20;
                          if (truncated) {
                            ArrayNode recent = mapper.createArrayNode();
                            // REST history is newest first; preserve that stable order.
                            for (int i = 0; i < 20; i++) recent.add(history.get(i));
                            copy.set("history", recent);
                          }
                          JsonNode card = copy.get("issue");
                          String summary =
                              "задача "
                                  + issue.key()
                                  + ", статус="
                                  + card.path("status").asText()
                                  + ", version="
                                  + card.path("version").asText()
                                  + (truncated ? "; показаны последние 20 событий истории" : "");
                          return new ToolData(copy, issue.context(), summary, truncated);
                        }));
  }

  private Mono<ToolData> listComments(ToolInvocation call) {
    return issueList(call, GatewayEndpoint.COMMENTS);
  }

  private Mono<ToolData> listWorklogs(ToolInvocation call) {
    return issueList(call, GatewayEndpoint.WORKLOGS);
  }

  private Mono<ToolData> issueList(ToolInvocation call, GatewayEndpoint endpoint) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue ->
                gateway
                    .get(endpoint, call.context(), issue.path(), page(call.arguments()))
                    .map(
                        data ->
                            new ToolData(
                                data,
                                issue.context(),
                                listSummary(data, call.arguments(), false))));
  }

  private Mono<ToolData> getBoard(ToolInvocation call) {
    return call.resolver()
        .project(call.arguments().key("project_key"))
        .flatMap(
            project -> {
              Map<String, Object> query = new LinkedHashMap<>();
              query.put("includeDone", call.arguments().bool("include_done", false));
              query.put("pageSizePerColumn", call.arguments().integer("page_size_per_column", 20));
              copy(
                  call.arguments(),
                  query,
                  "issue_type",
                  "issueType",
                  "priority",
                  "priority",
                  "query",
                  "query");
              Mono<Void> assignee =
                  call.arguments().has("assignee")
                      ? call.resolver()
                          .user(call.arguments().string("assignee"))
                          .doOnNext(value -> query.put("assigneeId", value))
                          .then()
                      : Mono.empty();
              Mono<Void> label =
                  call.arguments().has("label")
                      ? call.resolver()
                          .label(project, call.arguments().string("label"))
                          .doOnNext(value -> query.put("labelId", value))
                          .then()
                      : Mono.empty();
              return assignee
                  .then(label)
                  .then(
                      Mono.defer(
                          () ->
                              gateway.get(
                                  GatewayEndpoint.BOARD,
                                  call.context(),
                                  Map.of("projectId", project.id()),
                                  query)))
                  .map(
                      data ->
                          new ToolData(
                              data,
                              project.context(),
                              "доска "
                                  + project.key()
                                  + "; продолжение колонок через search_issues со status"));
            });
  }

  private Mono<ToolData> searchUsers(ToolInvocation call) {
    Map<String, Object> query = page(call.arguments());
    String text = call.arguments().string("query").trim();
    if (text.codePointCount(0, text.length()) < 3)
      return Mono.error(ToolArguments.invalid("Поиск пользователей требует минимум три символа."));
    query.put("query", text);
    return gateway
        .get(GatewayEndpoint.USERS, call.context(), Map.of(), query)
        .map(data -> new ToolData(data, listSummary(data, call.arguments(), false)));
  }

  private Mono<ToolData> listNotifications(ToolInvocation call) {
    int page = call.arguments().integer("page", 0),
        size = call.arguments().integer("page_size", 20);
    return gateway
        .get(
            GatewayEndpoint.NOTIFICATIONS,
            call.context(),
            Map.of(),
            Map.of(
                "offset",
                (long) page * size,
                "pageSize",
                size,
                "unreadOnly",
                call.arguments().bool("unread_only", false)))
        .map(data -> new ToolData(data, listSummary(data, call.arguments(), true)));
  }

  private Mono<Void> users(ToolInvocation call, Map<String, Object> query) {
    Mono<Void> assignee =
        call.arguments().has("assignee")
            ? call.resolver()
                .user(call.arguments().string("assignee"))
                .doOnNext(id -> query.put("assigneeId", id))
                .then()
            : Mono.empty();
    Mono<Void> reporter =
        call.arguments().has("reporter")
            ? call.resolver()
                .user(call.arguments().string("reporter"))
                .doOnNext(id -> query.put("reporterId", id))
                .then()
            : Mono.empty();
    return assignee.then(reporter);
  }

  private static Map<String, Object> page(ToolArguments args) {
    Map<String, Object> query = new LinkedHashMap<>();
    query.put("page", args.integer("page", 0));
    query.put("pageSize", args.integer("page_size", 20));
    return query;
  }

  private static void copy(ToolArguments args, Map<String, Object> query, String... names) {
    for (int i = 0; i < names.length; i += 2)
      if (args.has(names[i])) {
        String value = args.string(names[i]);
        if (value != null && !value.isBlank()) query.put(names[i + 1], value);
      }
  }

  private static String listSummary(JsonNode data, ToolArguments args, boolean approximate) {
    int count = ToolResolver.requiredArray(data, "items").size(),
        page = args.integer("page", 0),
        size = args.integer("page_size", 20);
    boolean more =
        data.has("hasMore")
            ? data.get("hasMore").asBoolean()
            : data.has("totalCount")
                ? ((long) page + 1) * size < data.get("totalCount").asLong()
                : count == size;
    if (approximate && more)
      return "элементов="
          + count
          + ", page="
          + page
          + "; чтобы проверить наличие следующей страницы, вызовите page="
          + ((long) page + 1)
          + ". unreadCount не является общим числом элементов списка.";
    return pageSummary(count, page, more);
  }

  private static String pageSummary(int count, int page, boolean more) {
    return "элементов="
        + count
        + ", page="
        + page
        + (more ? "; следующая страница: page=" + ((long) page + 1) : "; конец списка");
  }
}
