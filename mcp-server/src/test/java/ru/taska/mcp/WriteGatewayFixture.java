package ru.taska.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful HTTP boundary stub. It enforces REST concurrency/idempotency, not tool implementation.
 */
final class WriteGatewayFixture implements AutoCloseable {
  static final String USER = "11111111-1111-4111-8111-111111111111";
  static final String PROJECT = "22222222-2222-4222-8222-222222222222";
  static final String ISSUE = "33333333-3333-4333-8333-333333333333";
  static final String LABEL = "44444444-4444-4444-8444-444444444444";
  static final String TARGET = "55555555-5555-4555-8555-555555555555";
  static final String OPEN = "66666666-6666-4666-8666-666666666666";
  static final String READY = "77777777-7777-4777-8777-777777777777";
  static final String TRANSITION = "88888888-8888-4888-8888-888888888888";
  static final String TIME = "2026-10-20T10:00:00Z";
  static final String PP = "/api/v1/projects/" + PROJECT;
  static final String IP = PP + "/issues/" + ISSUE;
  final JsonMapper mapper = JsonMapper.builder().build();
  final ObjectNode issue = issue(ISSUE, "TAS-1");
  final ObjectNode target = issue(TARGET, "TAS-2");
  final ObjectNode project =
      mapper.createObjectNode().put("id", PROJECT).put("projectKey", "TAS").put("name", "Taska");
  final ObjectNode projectContext = context();
  final List<Call> calls = new CopyOnWriteArrayList<>();
  final AtomicInteger creates = new AtomicInteger(),
      patches = new AtomicInteger(),
      comments = new AtomicInteger(),
      worklogs = new AtomicInteger(),
      labels = new AtomicInteger(),
      links = new AtomicInteger();
  final AtomicBoolean conflictPatch = new AtomicBoolean(),
      conflictTransition = new AtomicBoolean(),
      failLabel = new AtomicBoolean(),
      disconnectComment = new AtomicBoolean();
  final Map<String, JsonNode> created = new ConcurrentHashMap<>(),
      fingerprints = new ConcurrentHashMap<>();
  final DisposableServer server;

  record Call(
      String method,
      String uri,
      String authorization,
      String requestId,
      String version,
      String idempotencyKey,
      JsonNode body) {}

  WriteGatewayFixture() {
    server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asString()
                        .defaultIfEmpty("")
                        .flatMap(
                            body ->
                                respond(
                                    request,
                                    response,
                                    body.isEmpty()
                                        ? mapper.createObjectNode()
                                        : mapper.readTree(body))))
            .bindNow();
  }

  @Override
  public void close() {
    server.disposeNow();
  }

  long count(String method, String path) {
    return calls.stream()
        .filter(call -> method.equals(call.method()) && path.equals(call.uri()))
        .count();
  }

  private Mono<Void> respond(
      HttpServerRequest request, HttpServerResponse response, JsonNode body) {
    String method = request.method().name(), path = request.uri().split("\\?", 2)[0];
    String version = request.requestHeaders().get(HttpHeaders.IF_MATCH),
        key = request.requestHeaders().get("Idempotency-Key");
    calls.add(
        new Call(
            method,
            request.uri(),
            request.requestHeaders().get(HttpHeaders.AUTHORIZATION),
            request.requestHeaders().get("X-Request-Id"),
            version,
            key,
            body));
    if (method.equals("GET")) {
      if (path.equals("/api/v1/projects"))
        return json(response, 200, Map.of("items", List.of(project)));
      if (path.equals(PP + "/context")) return json(response, 200, projectContext);
      if (path.equals("/api/v1/issues/by-key/TAS-1")) return json(response, 200, issue);
      if (path.equals("/api/v1/issues/by-key/TAS-2")) return json(response, 200, target);
      if (path.equals("/api/v1/users"))
        return json(
            response,
            200,
            Map.of(
                "items",
                List.of(Map.of("id", USER, "displayName", "Alice", "email", "alice@example.org")),
                "hasMore",
                false));
      return json(response, 404, Map.of());
    }
    if (method.equals("POST") && path.equals(PP + "/issues")) {
      if (key == null) return json(response, 400, Map.of());
      JsonNode fingerprint = fingerprints.putIfAbsent(key, body.deepCopy());
      if (fingerprint != null && !fingerprint.equals(body))
        return json(response, 400, Map.of("code", "FAILED_PRECONDITION"));
      JsonNode cached =
          created.computeIfAbsent(
              key,
              ignored -> {
                creates.incrementAndGet();
                issue
                    .put("issueType", body.path("issueType").asText())
                    .put("summary", body.path("summary").asText())
                    .put("description", body.path("description").asText())
                    .put("priority", body.path("priority").asText());
                return issue.deepCopy();
              });
      return json(response, 201, cached);
    }
    if (method.equals("PATCH") && path.equals("/api/v1/issues/" + ISSUE)) {
      if (conflictPatch.getAndSet(false)) issue.put("version", 7).put("status", "READY");
      if (!Integer.toString(issue.path("version").asInt()).equals(version))
        return json(response, 409, issue);
      patches.incrementAndGet();
      body.properties()
          .forEach(
              entry -> {
                if (entry.getValue().isNull()
                    && List.of("description", "assigneeId").contains(entry.getKey()))
                  issue.put(entry.getKey(), "");
                else issue.set(entry.getKey(), entry.getValue());
              });
      issue.put("version", issue.path("version").asInt() + 1);
      return json(response, 200, issue);
    }
    if (method.equals("PUT")
        && path.equals("/api/v1/issues/" + ISSUE + "/transition/" + TRANSITION)) {
      if (conflictTransition.getAndSet(false)) issue.put("version", 9).put("status", "READY");
      if (!Integer.toString(issue.path("version").asInt()).equals(version))
        return json(
            response,
            409,
            Map.of(
                "code",
                "ABORTED",
                "details",
                Map.of("currentVersion", "9", "currentStatus", "READY")));
      issue.put("status", "READY").put("version", issue.path("version").asInt() + 1);
      return json(response, 200, Map.of("issue", issue, "history", List.of()));
    }
    if (method.equals("POST") && path.equals(IP + "/comments")) {
      comments.incrementAndGet();
      if (disconnectComment.get()) {
        response.withConnection(connection -> connection.dispose());
        return Mono.empty();
      }
      return json(
          response,
          200,
          Map.of(
              "id",
              LABEL,
              "issueId",
              ISSUE,
              "projectId",
              PROJECT,
              "body",
              body.path("body").asText(),
              "createdAt",
              TIME,
              "version",
              1));
    }
    if (method.equals("POST") && path.equals(IP + "/worklogs")) {
      worklogs.incrementAndGet();
      return json(
          response,
          201,
          Map.of(
              "id",
              LABEL,
              "issueId",
              ISSUE,
              "projectId",
              PROJECT,
              "authorUserId",
              USER,
              "spentMinutes",
              body.path("spentMinutes").asInt(),
              "workDate",
              body.path("workDate").asText(),
              "comment",
              body.path("comment").asText(""),
              "createdAt",
              TIME));
    }
    if (path.equals(IP + "/labels") && method.equals("POST")) {
      if (failLabel.getAndSet(false)) return json(response, 503, Map.of("message", "private SQL"));
      labels.incrementAndGet();
      ((ArrayNode) issue.get("labels"))
          .add(mapper.valueToTree(Map.of("id", LABEL, "name", "bug", "color", "#112233")));
      return json(response, 201, Map.of("issueId", ISSUE, "labelId", LABEL));
    }
    if (path.equals(IP + "/labels/" + LABEL) && method.equals("DELETE")) {
      labels.incrementAndGet();
      issue.set("labels", mapper.createArrayNode());
      return response.status(204).send().then();
    }
    if (path.equals("/api/v1/issues/" + ISSUE + "/links") && method.equals("POST")) {
      links.incrementAndGet();
      return json(
          response,
          201,
          Map.of(
              "id",
              LABEL,
              "projectId",
              PROJECT,
              "sourceIssueId",
              ISSUE,
              "targetIssueId",
              TARGET,
              "viewLinkType",
              "ISSUE_LINK_VIEW_TYPE_BLOCKS",
              "createdAt",
              TIME));
    }
    if (path.equals(IP + "/watchers/me")) {
      if (method.equals("PUT"))
        return json(
            response,
            200,
            Map.of("watcher", Map.of("userId", USER, "issueId", ISSUE), "watchersCount", 1));
      if (method.equals("DELETE"))
        return json(response, 200, Map.of("issueId", ISSUE, "removed", true, "watchersCount", 0));
    }
    if (path.equals("/api/v1/notifications/" + LABEL + "/read") && method.equals("PATCH"))
      return json(response, 200, Map.of("id", LABEL, "readAt", TIME));
    return json(response, 404, Map.of());
  }

  private Mono<Void> json(HttpServerResponse response, int status, Object body) {
    return response
        .status(status)
        .header(HttpHeaders.CONTENT_TYPE, "application/json")
        .sendString(Mono.just(mapper.writeValueAsString(body)))
        .then();
  }

  private ObjectNode issue(String id, String key) {
    ObjectNode value =
        mapper
            .createObjectNode()
            .put("id", id)
            .put("projectId", PROJECT)
            .put("issueNumber", key.endsWith("1") ? 1 : 2)
            .put("issueKey", key)
            .put("issueType", "REQUEST")
            .put("summary", "Example")
            .put("description", "Description")
            .put("priority", "MEDIUM")
            .put("status", "OPEN")
            .put("assigneeId", "")
            .put("reporterId", USER)
            .put("createdAt", TIME)
            .put("updatedAt", TIME)
            .put("version", 1);
    value.set("labels", mapper.createArrayNode());
    return value;
  }

  private ObjectNode context() {
    ObjectNode value = mapper.createObjectNode();
    value.set("project", project);
    value.set("members", mapper.createArrayNode());
    value.set("labels", mapper.valueToTree(List.of(Map.of("id", LABEL, "name", "bug"))));
    value.set(
        "issueTypes",
        mapper.valueToTree(List.of(Map.of("code", "REQUEST"), Map.of("code", "TASK"))));
    value.set(
        "workflows",
        mapper.valueToTree(
            List.of(
                Map.of(
                    "issueType",
                    "REQUEST",
                    "workflow",
                    Map.of(
                        "statuses",
                            List.of(
                                Map.of("id", OPEN, "statusKey", "OPEN", "name", "Открыта"),
                                Map.of("id", READY, "statusKey", "READY", "name", "Готово")),
                        "transitions",
                            List.of(
                                Map.of(
                                    "id",
                                    TRANSITION,
                                    "fromStatusId",
                                    OPEN,
                                    "toStatusId",
                                    READY,
                                    "name",
                                    "Сделать")))))));
    return value;
  }
}
