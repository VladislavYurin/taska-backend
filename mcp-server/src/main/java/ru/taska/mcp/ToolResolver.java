package ru.taska.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Per-invocation lookups: never cache one user's authority or enrich a list with per-row RPCs. */
@Component
public class ToolResolver {
  private final GatewayClient gateway;

  public ToolResolver(GatewayClient gateway) {
    this.gateway = gateway;
  }

  public Session session(McpRequestContext context) {
    return new Session(context);
  }

  public record Project(String id, String key, String name, JsonNode value) {
    public Map<String, Object> context() {
      return Map.of("project_key", key, "project_name", name);
    }
  }

  public record Issue(
      String id,
      String projectId,
      String key,
      String summary,
      Map<String, Object> context,
      JsonNode value) {
    public Map<String, Object> path() {
      return Map.of("projectId", projectId, "issueId", id);
    }
  }

  public final class Session {
    private final McpRequestContext context;
    private final ToolAuditTarget auditTarget = new ToolAuditTarget();

    public ToolAuditTarget auditTarget() {
      return auditTarget;
    }

    private final Mono<JsonNode> projects;
    private final Map<String, Mono<Project>> projectLookups = new ConcurrentHashMap<>();
    private final Map<String, Mono<Issue>> issues = new ConcurrentHashMap<>();
    private final Map<String, Mono<JsonNode>> contexts = new ConcurrentHashMap<>();
    private final Map<String, Mono<String>> users = new ConcurrentHashMap<>();

    Session(McpRequestContext context) {
      this.context = context;
      // share() cancels the source when all callers cancel; cache() would keep the HTTP call alive.
      this.projects = gateway.get(GatewayEndpoint.PROJECTS, context, Map.of(), Map.of()).share();
    }

    public Mono<JsonNode> projects() {
      return projects;
    }

    public Mono<Project> project(String key) {
      String normalized = key.trim().toUpperCase(Locale.ROOT);
      return projectLookups.computeIfAbsent(
          normalized,
          ignored ->
              projects
                  .map(
                      body -> {
                        Project found = null;
                        for (JsonNode item : requiredArray(body, "items")) {
                          if (!normalized.equals(
                              requiredText(item, "projectKey").toUpperCase(Locale.ROOT))) continue;
                          if (found != null)
                            throw new GatewayToolException(
                                "upstream_error", "Ключ проекта неоднозначен в ответе Taska.");
                          found =
                              new Project(
                                  id(item, "id"),
                                  requiredText(item, "projectKey"),
                                  requiredText(item, "name"),
                                  item);
                        }
                        if (found == null) throw GatewayClient.notFound();
                        auditTarget.record(found.key());
                        return found;
                      })
                  .share());
    }

    public Mono<JsonNode> projectContext(Project project) {
      return contexts.computeIfAbsent(
          project.id(),
          ignored ->
              gateway
                  .get(
                      GatewayEndpoint.PROJECT_CONTEXT,
                      context,
                      Map.of("projectId", project.id()),
                      Map.of())
                  .share());
    }

    public Mono<Issue> issue(String key) {
      String normalized = key.trim().toUpperCase(Locale.ROOT);
      return issues.computeIfAbsent(
          normalized,
          ignored ->
              gateway
                  .get(
                      GatewayEndpoint.ISSUE_BY_KEY,
                      context,
                      Map.of("issueKey", normalized),
                      Map.of())
                  .zipWith(projects)
                  .map(
                      tuple -> {
                        JsonNode value = tuple.getT1();
                        String issueId = id(value, "id");
                        String projectId = id(value, "projectId");
                        String issueKey = requiredText(value, "issueKey");
                        String summary = requiredText(value, "summary");
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("issue_key", issueKey);
                        metadata.put("issue_summary", summary);
                        int dash = issueKey.lastIndexOf('-');
                        if (dash > 0) metadata.put("project_key", issueKey.substring(0, dash));
                        for (JsonNode project : requiredArray(tuple.getT2(), "items")) {
                          if (projectId.equals(project.path("id").asText())) {
                            metadata.put("project_key", requiredText(project, "projectKey"));
                            metadata.put("project_name", requiredText(project, "name"));
                            break;
                          }
                        }
                        auditTarget.record(issueKey);
                        return new Issue(
                            issueId, projectId, issueKey, summary, Map.copyOf(metadata), value);
                      })
                  .share());
    }

    public Mono<String> user(String selector) {
      String value = selector.trim();
      if (value.equalsIgnoreCase("me")) return Mono.just(context.user().getUserId());
      if (value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        return Mono.just(java.util.UUID.fromString(value).toString());
      if (value.length() < 3)
        return Mono.error(
            new GatewayToolException(
                "invalid_argument",
                "Для поиска пользователя укажите me, UUID либо имя/email от трёх символов."));
      return users.computeIfAbsent(
          value.toLowerCase(Locale.ROOT),
          ignored -> {
            int end = Math.min(value.length(), 100);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            return gateway
                .get(
                    GatewayEndpoint.USERS,
                    context,
                    Map.of(),
                    Map.of("query", value.substring(0, end), "page", 0, "pageSize", 50))
                .map(
                    body -> {
                      List<JsonNode> matches = new ArrayList<>();
                      for (JsonNode user : requiredArray(body, "items")) {
                        if (value.equalsIgnoreCase(user.path("email").asText())
                            || value.equalsIgnoreCase(user.path("displayName").asText()))
                          matches.add(user);
                      }
                      if (matches.size() == 1 && !body.path("hasMore").asBoolean())
                        return id(matches.getFirst(), "id");
                      if (matches.isEmpty() && !body.path("hasMore").asBoolean())
                        throw new GatewayToolException(
                            "invalid_argument",
                            "Пользователь не найден в доступном каталоге. Уточните имя/email или"
                                + " UUID.",
                            Map.of("reason", "USER_NOT_FOUND"));
                      List<Map<String, Object>> candidates = new ArrayList<>();
                      Iterable<JsonNode> values =
                          matches.isEmpty() ? requiredArray(body, "items") : matches;
                      for (JsonNode user : values) {
                        if (candidates.size() == 20) break;
                        candidates.add(
                            Map.of(
                                "id",
                                id(user, "id"),
                                "displayName",
                                requiredText(user, "displayName"),
                                "email",
                                requiredText(user, "email")));
                      }
                      throw new GatewayToolException(
                          "invalid_argument",
                          "Пользователь неоднозначен. Выберите точный UUID из candidates; результат"
                              + " не угадывается.",
                          Map.of(
                              "reason", "AMBIGUOUS_USER", "candidates", List.copyOf(candidates)));
                    })
                .share();
          });
    }

    public Mono<String> label(Project project, String selector) {
      return projectContext(project)
          .map(
              body -> {
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode label : requiredArray(body, "labels")) {
                  if (selector.trim().equals(label.path("id").asText())
                      || selector.trim().equals(label.path("name").asText())) matches.add(label);
                }
                if (matches.size() != 1)
                  throw new GatewayToolException(
                      "invalid_argument",
                      "Укажите точное id или уникальное имя существующей метки проекта.",
                      Map.of("reason", "LABEL_NOT_UNIQUE"));
                return id(matches.getFirst(), "id");
              });
    }
  }

  public static String requiredText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isString() || value.asText().isBlank())
      throw new GatewayToolException("upstream_error", "Taska вернула неполный ответ.");
    return value.asText();
  }

  public static JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isArray())
      throw new GatewayToolException("upstream_error", "Taska вернула неполный список.");
    return value;
  }

  private static String id(JsonNode node, String field) {
    try {
      return java.util.UUID.fromString(requiredText(node, field)).toString();
    } catch (IllegalArgumentException error) {
      throw new GatewayToolException("upstream_error", "Taska вернула некорректный идентификатор.");
    }
  }
}
