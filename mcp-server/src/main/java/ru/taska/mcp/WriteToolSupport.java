package ru.taska.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
public class WriteToolSupport {
  private final GatewayClient gateway;

  public WriteToolSupport(GatewayClient gateway) {
    this.gateway = gateway;
  }

  public Mono<JsonNode> fresh(ToolInvocation call, String issueKey) {
    return gateway.get(
        GatewayEndpoint.ISSUE_BY_KEY, call.context(), Map.of("issueKey", issueKey), Map.of());
  }

  public int version(ToolInvocation call, JsonNode state) {
    if (call.arguments().has("version")) return call.arguments().integer("version", 0);
    JsonNode version = state.get("version");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToInt()
        || version.asInt() < 1)
      throw new GatewayToolException(
          "upstream_error", "Taska не вернула допустимую версию задачи; запись не выполнялась.");
    return version.asInt();
  }

  public Mono<JsonNode> conditional(
      ToolInvocation call,
      ToolResolver.Issue issue,
      GatewayMutation operation,
      Map<String, ?> path,
      JsonNode body) {
    return gateway
        .write(
            operation,
            call.context(),
            path,
            body,
            GatewayWriteOptions.version(version(call, issue.value())))
        .onErrorResume(
            GatewayToolException.class,
            failure -> {
              if (!"conflict".equals(failure.code())) return Mono.error(failure);
              // Re-authorize and obtain current state, never automatically replay a stale write.
              return fresh(call, issue.key()).flatMap(current -> Mono.error(conflict(current)));
            });
  }

  public static GatewayToolException conflict(JsonNode current) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (current.path("version").isIntegralNumber())
      details.put("current_version", current.path("version").asInt());
    if (current.path("status").isString())
      details.put(
          "current_status", ToolResultFactory.codePoints(current.path("status").asText(), 64));
    return new GatewayToolException(
        "conflict",
        "Задача изменена другим действием. Перечитайте её и согласуйте изменение; автоматического"
            + " перезаписывания нет.",
        details);
  }

  public Mono<ToolResolver.Project> project(ToolInvocation call, ToolResolver.Issue issue) {
    Object key = issue.context().get("project_key");
    if (!(key instanceof String value)) return Mono.error(GatewayClient.notFound());
    return call.resolver()
        .project(value)
        .doOnNext(
            project -> {
              call.auditTarget().record(issue.key());
              requireActive(project);
            });
  }

  public static void requireActive(ToolResolver.Project project) {
    JsonNode archived = project.value().get("archivedAt");
    if (archived != null && !archived.isNull())
      throw new GatewayToolException(
          "failed_precondition",
          "Проект архивирован; изменение не выполнялось.",
          Map.of("reason", "PROJECT_ARCHIVED"));
  }

  public static String type(JsonNode projectContext, String requested) {
    var allowed = new ArrayList<String>();
    for (JsonNode value : ToolResolver.requiredArray(projectContext, "issueTypes"))
      allowed.add(ToolResolver.requiredText(value, "code"));
    if (requested == null && !allowed.isEmpty()) return allowed.getFirst();
    if (requested != null && allowed.contains(requested)) return requested;
    throw new GatewayToolException(
        "invalid_argument",
        "Выберите тип из реестра проекта; неизвестный тип не заменяется на TASK.",
        Map.of(
            "reason",
            "ISSUE_TYPE_NOT_ALLOWED",
            "allowed_values",
            allowed.stream().limit(100).toList()));
  }

  public static String label(JsonNode projectContext, String selector) {
    List<String> matches = new ArrayList<>();
    List<String> allowed = new ArrayList<>();
    for (JsonNode value : ToolResolver.requiredArray(projectContext, "labels")) {
      String id = ToolResolver.requiredText(value, "id"),
          name = ToolResolver.requiredText(value, "name");
      allowed.add(ToolResultFactory.codePoints(name, 100));
      if (selector.trim().equals(id) || selector.trim().equals(name)) matches.add(id);
    }
    if (matches.size() == 1) return matches.getFirst();
    throw new GatewayToolException(
        "invalid_argument",
        "Укажите id или уникальное имя существующей метки проекта; новая метка не создаётся.",
        Map.of(
            "reason", "LABEL_NOT_UNIQUE", "allowed_values", allowed.stream().limit(50).toList()));
  }

  public static boolean hasLabel(JsonNode issue, String id) {
    for (JsonNode label : ToolResolver.requiredArray(issue, "labels"))
      if (id.equals(label.path("id").asText())) return true;
    return false;
  }

  public static String summary(JsonNode issue) {
    return "задача "
        + issue.path("issueKey").asText()
        + ", статус="
        + issue.path("status").asText()
        + ", version="
        + issue.path("version").asText();
  }
}
