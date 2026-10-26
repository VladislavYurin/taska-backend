package ru.taska.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WriteTools implements ToolContributor {
  private static final Map<String, String> PATCH =
      Map.ofEntries(
          Map.entry("summary", "summary"),
          Map.entry("description", "description"),
          Map.entry("priority", "priority"),
          Map.entry("issue_type", "issueType"),
          Map.entry("story_points", "storyPoints"),
          Map.entry("start_date", "startDate"),
          Map.entry("due_date", "dueDate"),
          Map.entry("original_estimate_minutes", "originalEstimateMinutes"),
          Map.entry("remaining_estimate_minutes", "remainingEstimateMinutes"));
  private final GatewayClient gateway;
  private final WriteToolSupport support;
  private final ToolSchemas schemas;
  private final JsonMapper mapper;

  public WriteTools(
      GatewayClient gateway, WriteToolSupport support, ToolSchemas schemas, JsonMapper mapper) {
    this.gateway = gateway;
    this.support = support;
    this.schemas = schemas;
    this.mapper = mapper;
  }

  @Override
  public List<ToolRegistration> tools() {
    return List.of(
        tool(
            "create_issue",
            "Создать одну задачу после разрешения пользователя. Обязательный idempotency_key —"
                + " новый UUID на новое намерение; при повторе те же UUID и аргументы."
                + " Назначение/метки отдельными шагами; partial_success возвращает созданный ключ и"
                + " оставшиеся шаги.",
            true,
            this::createIssue),
        tool(
            "update_issue",
            "Условно изменить поля задачи. PATCH сохраняет отсутствующие поля, явный null очищает"
                + " допустимые поля. При конфликте перечитать текущую версию; не перезаписывать"
                + " автоматически.",
            false,
            this::updateIssue),
        tool(
            "transition_issue",
            "Перевести одну задачу в точный целевой статус/key её workflow с If-Match. Уже"
                + " достигнутый статус — без записи. При отсутствии перехода показаны разрешённые"
                + " варианты.",
            true,
            this::transitionIssue),
        tool(
            "assign_issue",
            "Условно назначить одного исполнителя (me, UUID, точное имя/email); null снимает"
                + " исполнителя. Неоднозначность возвращает candidates. Тот же исполнитель — без"
                + " записи.",
            true,
            this::assignIssue),
        tool(
            "add_comment",
            "Добавить один комментарий (до 10000 символов). Durable idempotency отсутствует: после"
                + " сетевого обрыва не повторять автоматически; сначала list_comments, затем"
                + " уточнить намерение при неоднозначности.",
            false,
            call ->
                issueWrite(
                    call,
                    GatewayMutation.COMMENT,
                    Map.of("body", call.arguments().string("body")),
                    "комментарий добавлен")),
        tool(
            "log_work",
            "Добавить один worklog за дату, 1..1440 минут, comment до 2000 символов. Durable"
                + " idempotency отсутствует: после обрыва сначала list_worklogs, автоматический"
                + " повтор запрещён.",
            false,
            this::logWork),
        tool(
            "add_label",
            "Привязать существующую метку проекта к одной задаче; существующая привязка не создаёт"
                + " событие повторно.",
            true,
            call -> label(call, true)),
        tool(
            "remove_label",
            "Снять только привязку метки к задаче; сама метка не удаляется. Отсутствующая привязка"
                + " — без записи.",
            true,
            call -> label(call, false)),
        tool(
            "link_issues",
            "Связать две доступные задачи. Durable idempotency отсутствует: после обрыва перечитать"
                + " связи через get_issue, не повторять автоматически при неоднозначности.",
            false,
            this::linkIssues),
        tool(
            "watch_issue",
            "Подписать текущего пользователя на одну задачу; повтор безопасен.",
            true,
            call -> issueWrite(call, GatewayMutation.WATCH, null, "подписка установлена")),
        tool(
            "unwatch_issue",
            "Отписать текущего пользователя от одной задачи; повтор безопасен.",
            true,
            call -> issueWrite(call, GatewayMutation.UNWATCH, null, "подписка снята")),
        tool(
            "mark_notifications_read",
            "Отметить прочитанным ровно одно собственное уведомление по UUID; массовой операции"
                + " нет, повтор безопасен.",
            true,
            this::markNotification));
  }

  private ToolRegistration tool(
      String name,
      String description,
      boolean idempotent,
      Function<ToolInvocation, Mono<ToolData>> handler) {
    return new ToolRegistration(
        schemas.definition(
            "/schemas/write-tools.json", name, description, "write", false, idempotent),
        handler);
  }

  private Mono<ToolData> updateIssue(ToolInvocation call) {
    if (call.arguments().has("summary") && call.arguments().string("summary").isBlank())
      return Mono.error(ToolArguments.invalid("summary не может состоять из пробелов."));
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue -> {
              ObjectNode body = mapper.createObjectNode();
              PATCH.forEach(
                  (argument, field) -> {
                    if (call.arguments().has(argument))
                      body.set(field, mapper.valueToTree(call.arguments().values().get(argument)));
                  });
              Mono<Void> validate =
                  call.arguments().has("issue_type")
                      ? support
                          .project(call, issue)
                          .flatMap(project -> call.resolver().projectContext(project))
                          .doOnNext(
                              context ->
                                  WriteToolSupport.type(
                                      context, call.arguments().string("issue_type")))
                          .then()
                      : Mono.empty();
              return validate
                  .then(
                      Mono.defer(
                          () ->
                              support.conditional(
                                  call,
                                  issue,
                                  GatewayMutation.PATCH_ISSUE,
                                  Map.of("issueId", issue.id()),
                                  body)))
                  .map(data -> new ToolData(data, issue.context(), WriteToolSupport.summary(data)));
            });
  }

  private Mono<ToolData> assignIssue(ToolInvocation call) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue -> {
              String requested = call.arguments().string("assignee");
              Mono<String> selector =
                  requested == null ? Mono.just("") : call.resolver().user(requested);
              return selector.flatMap(
                  id -> {
                    if (id.equals(issue.value().path("assigneeId").asText("")))
                      return Mono.just(
                          new ToolData(
                              issue.value(),
                              issue.context(),
                              WriteToolSupport.summary(issue.value())
                                  + "; исполнитель уже установлен"));
                    ObjectNode body = mapper.createObjectNode();
                    if (requested == null) body.putNull("assigneeId");
                    else body.put("assigneeId", id);
                    return support
                        .conditional(
                            call,
                            issue,
                            GatewayMutation.PATCH_ISSUE,
                            Map.of("issueId", issue.id()),
                            body)
                        .map(
                            data ->
                                new ToolData(
                                    data, issue.context(), WriteToolSupport.summary(data)));
                  });
            });
  }

  private Mono<ToolData> transitionIssue(ToolInvocation call) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue ->
                support
                    .project(call, issue)
                    .flatMap(project -> call.resolver().projectContext(project))
                    .flatMap(
                        context -> {
                          String type = ToolResolver.requiredText(issue.value(), "issueType"),
                              status = ToolResolver.requiredText(issue.value(), "status");
                          JsonNode workflow = null;
                          for (JsonNode entry : ToolResolver.requiredArray(context, "workflows"))
                            if (type.equals(entry.path("issueType").asText())) {
                              if (workflow != null)
                                return Mono.error(
                                    new GatewayToolException(
                                        "upstream_error", "Workflow типа задачи неоднозначен."));
                              workflow = entry.get("workflow");
                            }
                          if (workflow == null)
                            return Mono.error(
                                new GatewayToolException(
                                    "failed_precondition",
                                    "Для собственного типа задачи нет workflow.",
                                    Map.of("reason", "WORKFLOW_NOT_CONFIGURED")));
                          String target = call.arguments().string("target_status").trim();
                          List<JsonNode> targets = new ArrayList<>();
                          String currentId = null;
                          for (JsonNode value : ToolResolver.requiredArray(workflow, "statuses")) {
                            if (status.equals(value.path("statusKey").asText()))
                              currentId = ToolResolver.requiredText(value, "id");
                            if (target.equals(value.path("statusKey").asText())
                                || target.equals(value.path("name").asText())
                                || target.equals(value.path("id").asText())) targets.add(value);
                          }
                          List<String> allowed = new ArrayList<>();
                          List<String> matches = new ArrayList<>();
                          for (JsonNode edge :
                              ToolResolver.requiredArray(workflow, "transitions")) {
                            if (currentId == null
                                || !currentId.equals(edge.path("fromStatusId").asText())
                                || edge.path("hidden").asBoolean(false)) continue;
                            for (JsonNode value : ToolResolver.requiredArray(workflow, "statuses"))
                              if (value
                                  .path("id")
                                  .asText()
                                  .equals(edge.path("toStatusId").asText()))
                                allowed.add(
                                    ToolResultFactory.codePoints(
                                        value.path("statusKey").asText()
                                            + " ("
                                            + value.path("name").asText()
                                            + ")",
                                        200));
                            if (targets.size() == 1
                                && targets
                                    .getFirst()
                                    .path("id")
                                    .asText()
                                    .equals(edge.path("toStatusId").asText()))
                              matches.add(ToolResolver.requiredText(edge, "id"));
                          }
                          if (targets.size() == 1
                              && status.equals(targets.getFirst().path("statusKey").asText()))
                            return Mono.just(
                                new ToolData(
                                    mapper.valueToTree(
                                        Map.of("issue", issue.value(), "history", List.of())),
                                    issue.context(),
                                    WriteToolSupport.summary(issue.value())
                                        + "; целевой статус уже достигнут"));
                          if (targets.size() != 1 || matches.size() != 1)
                            return Mono.error(
                                new GatewayToolException(
                                    "invalid_argument",
                                    "Нет однозначного разрешённого перехода из текущего статуса."
                                        + " Выберите доступный целевой статус.",
                                    Map.of(
                                        "reason",
                                        "TRANSITION_NOT_AVAILABLE",
                                        "allowed_values",
                                        allowed.stream().distinct().limit(100).toList())));
                          return support
                              .conditional(
                                  call,
                                  issue,
                                  GatewayMutation.TRANSITION_ISSUE,
                                  Map.of("issueId", issue.id(), "transitionId", matches.getFirst()),
                                  null)
                              .map(
                                  data ->
                                      new ToolData(
                                          data,
                                          issue.context(),
                                          WriteToolSupport.summary(data.path("issue"))));
                        }));
  }

  private Mono<ToolData> logWork(ToolInvocation call) {
    try {
      java.time.LocalDate.parse(call.arguments().string("work_date"));
    } catch (java.time.format.DateTimeParseException error) {
      return Mono.error(
          ToolArguments.invalid("work_date должна быть существующей датой YYYY-MM-DD."));
    }
    if (call.arguments().has("comment") && call.arguments().string("comment").length() > 2000)
      return Mono.error(
          new GatewayToolException(
              "invalid_argument",
              "comment: максимум 2000 символов UTF-16.",
              Map.of("reason", "TEXT_LIMIT")));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("spentMinutes", call.arguments().integer("spent_minutes", 0));
    body.put("workDate", call.arguments().string("work_date"));
    if (call.arguments().has("comment")) body.put("comment", call.arguments().string("comment"));
    return issueWrite(call, GatewayMutation.WORKLOG, body, "worklog добавлен");
  }

  private Mono<ToolData> issueWrite(
      ToolInvocation call, GatewayMutation operation, Map<String, Object> body, String summary) {
    if (body != null
        && body.get("body") instanceof String text
        && (text.isBlank() || text.length() > 10000))
      return Mono.error(
          new GatewayToolException(
              "invalid_argument",
              "body: от 1 до 10000 символов UTF-16, не только пробелы.",
              Map.of("reason", "TEXT_LIMIT")));
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue ->
                gateway
                    .write(
                        operation,
                        call.context(),
                        issue.path(),
                        body == null ? null : mapper.valueToTree(body),
                        GatewayWriteOptions.none())
                    .map(
                        data ->
                            new ToolData(
                                data, issue.context(), summary + "; задача " + issue.key())));
  }

  private Mono<ToolData> label(ToolInvocation call, boolean add) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            issue ->
                support
                    .project(call, issue)
                    .flatMap(project -> call.resolver().projectContext(project))
                    .flatMap(
                        context -> {
                          String id =
                              WriteToolSupport.label(context, call.arguments().string("label"));
                          boolean exists = WriteToolSupport.hasLabel(issue.value(), id);
                          if (exists == add) return Mono.just(labelData(issue, issue.value()));
                          Map<String, Object> path = new LinkedHashMap<>(issue.path());
                          if (!add) path.put("labelId", id);
                          return gateway
                              .write(
                                  add ? GatewayMutation.ADD_LABEL : GatewayMutation.REMOVE_LABEL,
                                  call.context(),
                                  path,
                                  add ? mapper.valueToTree(Map.of("labelId", id)) : null,
                                  GatewayWriteOptions.none())
                              .then(support.fresh(call, issue.key()))
                              .map(current -> labelData(issue, current));
                        }));
  }

  private ToolData labelData(ToolResolver.Issue issue, JsonNode current) {
    return new ToolData(
        mapper.valueToTree(
            Map.of("issue", current, "labels", ToolResolver.requiredArray(current, "labels"))),
        issue.context(),
        WriteToolSupport.summary(current));
  }

  private Mono<ToolData> linkIssues(ToolInvocation call) {
    return call.resolver()
        .issue(call.arguments().key("issue_key"))
        .flatMap(
            source ->
                call.resolver()
                    .issue(call.arguments().key("target_issue_key"))
                    .flatMap(
                        target -> {
                          if (source.id().equals(target.id()))
                            return Mono.error(
                                ToolArguments.invalid("Нельзя связать задачу с самой собой."));
                          call.auditTarget().record(source.key());
                          return gateway
                              .write(
                                  GatewayMutation.LINK_ISSUES,
                                  call.context(),
                                  Map.of("issueId", source.id()),
                                  mapper.valueToTree(
                                      Map.of(
                                          "targetIssueId",
                                          target.id(),
                                          "linkType",
                                          call.arguments().string("link_type"))),
                                  GatewayWriteOptions.none())
                              .map(
                                  data ->
                                      new ToolData(
                                          data,
                                          source.context(),
                                          "связь "
                                              + source.key()
                                              + " → "
                                              + target.key()
                                              + " создана"));
                        }));
  }

  private Mono<ToolData> markNotification(ToolInvocation call) {
    String id = call.arguments().string("notification_id");
    call.auditTarget().record(id);
    return gateway
        .write(
            GatewayMutation.MARK_NOTIFICATION_READ,
            call.context(),
            Map.of("notificationId", id),
            null,
            GatewayWriteOptions.none())
        .map(
            notification -> {
              if (!id.equals(notification.path("id").asText())
                  || !notification.path("readAt").isString())
                throw new GatewayToolException(
                    "upstream_error",
                    "Состояние уведомления не подтверждено. Не повторяйте запись автоматически;"
                        + " перечитайте list_notifications.",
                    Map.of("reason", "WRITE_OUTCOME_UNKNOWN"));
              return new ToolData(
                  mapper.valueToTree(Map.of("notification_id", id, "read", true)),
                  "уведомление отмечено прочитанным");
            });
  }

  private record CreatePlan(
      ToolResolver.Project project, String type, String assignee, List<String> labels) {}

  private Mono<ToolData> createIssue(ToolInvocation call) {
    if (call.arguments().string("summary").isBlank())
      return Mono.error(ToolArguments.invalid("summary не может состоять из пробелов."));
    return call.resolver()
        .project(call.arguments().key("project_key"))
        .flatMap(
            project -> {
              WriteToolSupport.requireActive(project);
              return call.resolver()
                  .projectContext(project)
                  .flatMap(
                      context -> {
                        String type =
                            WriteToolSupport.type(context, call.arguments().string("issue_type"));
                        List<String> labels = new ArrayList<>();
                        if (call.arguments().has("labels"))
                          for (Object selector :
                              (List<?>) call.arguments().values().get("labels")) {
                            String id = WriteToolSupport.label(context, selector.toString());
                            if (!labels.contains(id)) labels.add(id);
                          }
                        Mono<String> assignee =
                            call.arguments().has("assignee")
                                ? call.resolver().user(call.arguments().string("assignee"))
                                : Mono.just("");
                        return assignee.map(
                            id -> new CreatePlan(project, type, id, List.copyOf(labels)));
                      });
            })
        .flatMap(
            plan -> {
              Map<String, Object> body =
                  Map.of(
                      "issueType",
                      plan.type(),
                      "summary",
                      call.arguments().string("summary"),
                      "description",
                      call.arguments().has("description")
                          ? call.arguments().string("description")
                          : "",
                      "priority",
                      call.arguments().has("priority")
                          ? call.arguments().string("priority")
                          : "MEDIUM");
              return gateway
                  .write(
                      GatewayMutation.CREATE_ISSUE,
                      call.context(),
                      Map.of("projectId", plan.project().id()),
                      mapper.valueToTree(body),
                      GatewayWriteOptions.create(call.arguments().string("idempotency_key")))
                  .flatMap(created -> completeCreate(call, plan, created));
            });
  }

  private Mono<ToolData> completeCreate(ToolInvocation call, CreatePlan plan, JsonNode created) {
    String key = ToolResolver.requiredText(created, "issueKey");
    call.auditTarget().record(key);
    List<String> completed = new ArrayList<>(List.of("create"));
    List<String> remaining = new ArrayList<>();
    if (!plan.assignee().isEmpty()) remaining.add("assign");
    for (String id : plan.labels()) remaining.add("label:" + id);
    remaining.add("verify_state");
    return support
        .fresh(call, key)
        .flatMap(
            current -> {
              String id = ToolResolver.requiredText(current, "id");
              Mono<JsonNode> assign = Mono.just(current);
              if (!plan.assignee().isEmpty()) {
                if (plan.assignee().equals(current.path("assigneeId").asText())) {
                  remaining.remove("assign");
                  completed.add("assign");
                } else if (!current.path("assigneeId").asText("").isEmpty())
                  return Mono.error(WriteToolSupport.conflict(current));
                else
                  assign =
                      gateway
                          .write(
                              GatewayMutation.PATCH_ISSUE,
                              call.context(),
                              Map.of("issueId", id),
                              mapper.valueToTree(Map.of("assigneeId", plan.assignee())),
                              GatewayWriteOptions.version(current.path("version").asInt()))
                          .doOnNext(
                              value -> {
                                remaining.remove("assign");
                                completed.add("assign");
                              });
              }
              return assign
                  .flatMap(
                      state ->
                          Flux.fromIterable(plan.labels())
                              .concatMap(
                                  label -> {
                                    String step = "label:" + label;
                                    Mono<?> action =
                                        WriteToolSupport.hasLabel(state, label)
                                            ? Mono.just(state)
                                            : gateway.write(
                                                GatewayMutation.ADD_LABEL,
                                                call.context(),
                                                Map.of(
                                                    "projectId",
                                                    plan.project().id(),
                                                    "issueId",
                                                    id),
                                                mapper.valueToTree(Map.of("labelId", label)),
                                                GatewayWriteOptions.none());
                                    return action.doOnNext(
                                        ignored -> {
                                          remaining.remove(step);
                                          completed.add(step);
                                        });
                                  })
                              .then(support.fresh(call, key)))
                  .map(
                      state -> {
                        remaining.remove("verify_state");
                        completed.add("verify_state");
                        Map<String, Object> metadata =
                            new LinkedHashMap<>(plan.project().context());
                        metadata.put("issue_key", key);
                        metadata.put("issue_summary", state.path("summary").asText());
                        return new ToolData(state, metadata, WriteToolSupport.summary(state));
                      });
            })
        .onErrorMap(
            GatewayToolException.class,
            failure ->
                new GatewayToolException(
                    "partial_success",
                    "Задача "
                        + key
                        + " создана, дополнительные шаги завершены не полностью. Не создавайте"
                        + " новую задачу: тот же idempotency_key и аргументы завершат только"
                        + " отсутствующие шаги. При конфликте исполнителя используйте assign_issue"
                        + " после согласования.",
                    Map.of(
                        "created_issue_key",
                        key,
                        "completed_steps",
                        List.copyOf(completed),
                        "remaining_steps",
                        List.copyOf(remaining),
                        "reason",
                        failure.code().toUpperCase(java.util.Locale.ROOT))));
  }
}
