package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.taska.mcp.WriteGatewayFixture.ISSUE;
import static ru.taska.mcp.WriteGatewayFixture.LABEL;
import static ru.taska.mcp.WriteGatewayFixture.PP;
import static ru.taska.mcp.WriteGatewayFixture.TIME;
import static ru.taska.mcp.WriteGatewayFixture.TRANSITION;
import static ru.taska.mcp.WriteGatewayFixture.USER;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WriteToolsTest {
  static final String KEY = "99999999-9999-4999-8999-999999999999";
  final JsonMapper mapper = JsonMapper.builder().build();
  WriteGatewayFixture fixture;
  List<ToolRegistration> tools;
  ToolExecutor executor;
  McpRequestContext context;

  @BeforeEach
  void setup() {
    fixture = new WriteGatewayFixture();
    context =
        new McpRequestContext(
            "write-test",
            McpAuthenticationFilterTest.TOKEN,
            McpAuthenticationFilterTest.user().toBuilder()
                .setUserId(USER)
                .addScopes("write")
                .build());
    var gateway =
        new GatewayClient(
            WebClient.builder(),
            McpAuthenticationFilterTest.properties(
                URI.create("http://localhost:" + fixture.server.port()),
                Duration.ofSeconds(2),
                2 * 1024 * 1024));
    var resolver = new ToolResolver(gateway);
    executor =
        new ToolExecutor(
            resolver,
            new ToolResultFactory(mapper, ToolLimits.defaults()),
            ToolLimits.defaults(),
            new DefaultListableBeanFactory().getBeanProvider(ToolInterceptor.class));
    tools =
        new WriteTools(gateway, new WriteToolSupport(gateway), new ToolSchemas(mapper), mapper)
            .tools();
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  static Stream<String> names() {
    return Stream.of(
        "create_issue",
        "update_issue",
        "transition_issue",
        "assign_issue",
        "add_comment",
        "log_work",
        "add_label",
        "remove_label",
        "link_issues",
        "watch_issue",
        "unwatch_issue",
        "mark_notifications_read");
  }

  @ParameterizedTest
  @MethodSource("names")
  void eachWriteHasValidFinalOutputAndPropagatesUserCredentials(String name) {
    var result = call(name, args(name));
    assertThat(result.isError()).as(name + " " + result.structuredContent()).isFalse();
    assertThat(content(result).has("data")).isTrue();
    assertThat(content(result).path("request_id").asText()).isEqualTo("write-test");
    assertThat(mapper.writeValueAsString(result)).doesNotContain(context.bearerToken(), "token-1");
    assertThat(fixture.calls)
        .allSatisfy(
            request -> {
              assertThat(request.authorization()).isEqualTo("Bearer " + context.bearerToken());
              assertThat(request.requestId()).isEqualTo("write-test");
            });
    assertThat(
            tools.stream()
                .filter(tool -> name.equals(tool.definition().name()))
                .findFirst()
                .orElseThrow()
                .definition()
                .scope())
        .isEqualTo("write");
  }

  @Test
  void createRepeatWithSameKeyNeverDuplicatesOrReappliesCompletedSteps() {
    Map<String, Object> input =
        Map.of(
            "project_key",
            " tas ",
            "summary",
            "New",
            "assignee",
            "me",
            "labels",
            List.of("bug"),
            "idempotency_key",
            KEY);
    assertThat(call("create_issue", input).isError()).isFalse();
    assertThat(call("create_issue", input).isError()).isFalse();
    assertThat(fixture.creates).hasValue(1);
    assertThat(fixture.patches).hasValue(1);
    assertThat(fixture.labels).hasValue(1);
    var posts =
        fixture.calls.stream()
            .filter(
                request -> request.method().equals("POST") && request.uri().equals(PP + "/issues"))
            .toList();
    assertThat(posts)
        .hasSize(2)
        .allSatisfy(request -> assertThat(request.idempotencyKey()).isEqualTo(KEY));
    assertThat(posts.getFirst().body().path("issueType").asText()).isEqualTo("REQUEST");
    assertThat(posts.getFirst().body().path("description").asText()).isEmpty();
  }

  @Test
  void partialCreateReturnsCreatedKeyAndRetryCompletesOnlyMissingLabel() {
    fixture.failLabel.set(true);
    Map<String, Object> input =
        Map.of(
            "project_key",
            "TAS",
            "summary",
            "New",
            "assignee",
            "me",
            "labels",
            List.of("bug"),
            "idempotency_key",
            KEY);
    var first = content(call("create_issue", input));
    assertThat(first.path("error").path("code").asText()).isEqualTo("partial_success");
    assertThat(first.path("error").path("details").path("created_issue_key").asText())
        .isEqualTo("TAS-1");
    assertThat(first.path("error").path("details").path("completed_steps").toString())
        .contains("assign");
    assertThat(first.path("error").path("details").path("remaining_steps").toString())
        .contains("label:" + LABEL);
    assertThat(call("create_issue", input).isError()).isFalse();
    assertThat(fixture.creates).hasValue(1);
    assertThat(fixture.patches).hasValue(1);
    assertThat(fixture.labels).hasValue(1);
  }

  @Test
  void partialCreateRetainsHumanAssignmentConflictDetailsWithoutOverwriting() {
    // Current authorized post-create state already has a human's assignment.
    String humanAssignee = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    fixture.issue.put("assigneeId", humanAssignee).put("version", 4);
    var result =
        call(
            "create_issue",
            Map.of(
                "project_key", "TAS", "summary", "New", "assignee", "me", "idempotency_key", KEY));
    var error = content(result).path("error");
    assertThat(error.path("code").asText()).isEqualTo("partial_success");
    assertThat(error.path("details").path("created_issue_key").asText()).isEqualTo("TAS-1");
    assertThat(error.path("details").path("reason").asText()).isEqualTo("CONFLICT");
    assertThat(error.path("details").path("current_version").asInt()).isEqualTo(4);
    assertThat(error.path("details").path("current_status").asText()).isEqualTo("OPEN");
    assertThat(error.path("details").path("remaining_steps").toString()).contains("assign");
    assertThat(fixture.creates).hasValue(1);
    assertThat(fixture.patches).hasValue(0);
    assertThat(fixture.issue.path("assigneeId").asText()).isEqualTo(humanAssignee);
    assertThat(error.path("details").has("assigneeId")).isFalse();
  }

  @Test
  void createValidatesRegistryLabelsAndAssigneeBeforeFirstWrite() {
    for (Map<String, Object> extra :
        List.of(
            Map.<String, Object>of("issue_type", "MISSING"),
            Map.<String, Object>of("labels", List.of("missing")))) {
      var input = new java.util.LinkedHashMap<>(args("create_issue"));
      input.putAll(extra);
      var result = content(call("create_issue", input));
      assertThat(result.path("error").path("code").asText()).isEqualTo("invalid_argument");
      assertThat(result.path("error").path("details").path("allowed_values")).isNotEmpty();
    }
    assertThat(fixture.creates).hasValue(0);
    assertThat(fixture.calls).allMatch(request -> request.method().equals("GET"));
  }

  @Test
  void conditionalPatchPreservesAbsentFieldsAndExplicitNullAndUsesObservedVersion() {
    var input =
        new java.util.LinkedHashMap<String, Object>(
            Map.of("issue_key", "TAS-1", "summary", "Changed"));
    input.put("description", null);
    var result = call("update_issue", input);
    assertThat(result.isError()).isFalse();
    var request =
        fixture.calls.stream()
            .filter(value -> value.method().equals("PATCH"))
            .findFirst()
            .orElseThrow();
    assertThat(request.version()).isEqualTo("1");
    assertThat(request.body().path("description").isNull()).isTrue();
    assertThat(request.body().has("priority")).isFalse();
    assertThat(content(result).path("data").path("priority").asText()).isEqualTo("MEDIUM");
    assertThat(content(result).path("data").path("version").asInt()).isEqualTo(2);
  }

  @Test
  void versionConflictReadsCurrentStateAndNeverRetriesPatch() {
    fixture.conflictPatch.set(true);
    var result = content(call("update_issue", args("update_issue")));
    assertThat(result.path("error").path("code").asText()).isEqualTo("conflict");
    assertThat(result.path("error").path("details").path("current_version").asInt()).isEqualTo(7);
    assertThat(result.path("error").path("details").path("current_status").asText())
        .isEqualTo("READY");
    assertThat(fixture.count("PATCH", "/api/v1/issues/" + ISSUE)).isEqualTo(1);
    assertThat(fixture.count("GET", "/api/v1/issues/by-key/TAS-1")).isEqualTo(2);
    assertThat(fixture.patches).hasValue(0);
  }

  @Test
  void suppliedVersionIsNeverReplacedByFreshObservedVersion() {
    fixture.issue.put("version", 4);
    var error =
        content(
                call(
                    "update_issue",
                    Map.of("issue_key", "TAS-1", "version", 1, "summary", "Stale edit")))
            .path("error");
    assertThat(error.path("code").asText()).isEqualTo("conflict");
    assertThat(error.path("details").path("current_version").asInt()).isEqualTo(4);
    assertThat(
            fixture.calls.stream()
                .filter(value -> value.method().equals("PATCH"))
                .findFirst()
                .orElseThrow()
                .version())
        .isEqualTo("1");
    assertThat(fixture.patches).hasValue(0);
  }

  @Test
  void inaccessibleTargetAndArchivedProjectCauseNoBusinessWrites() {
    var inaccessible =
        content(
            call(
                "link_issues",
                Map.of(
                    "issue_key", "TAS-1", "target_issue_key", "HIDDEN-1", "link_type", "BLOCKS")));
    assertThat(inaccessible.path("error").path("code").asText()).isEqualTo("not_found");
    fixture.project.put("archivedAt", TIME);
    var archived = content(call("create_issue", args("create_issue")));
    assertThat(archived.path("error").path("details").path("reason").asText())
        .isEqualTo("PROJECT_ARCHIVED");
    assertThat(fixture.calls).allMatch(value -> value.method().equals("GET"));
  }

  @Test
  void assignmentSameUserAndClearedUserAreNoOps() {
    fixture.issue.put("assigneeId", USER);
    assertThat(call("assign_issue", Map.of("issue_key", "TAS-1", "assignee", "me")).isError())
        .isFalse();
    fixture.issue.put("assigneeId", "");
    var clear = new java.util.LinkedHashMap<String, Object>();
    clear.put("issue_key", "TAS-1");
    clear.put("assignee", null);
    assertThat(call("assign_issue", clear).isError()).isFalse();
    assertThat(fixture.patches).hasValue(0);
  }

  @Test
  void transitionsUseOwnTypeAndNoOpAtTargetAndRejectUnavailableTarget() {
    assertThat(
            call("transition_issue", Map.of("issue_key", "TAS-1", "target_status", "Готово"))
                .isError())
        .isFalse();
    assertThat(
            call("transition_issue", Map.of("issue_key", "TAS-1", "target_status", "READY"))
                .isError())
        .isFalse();
    assertThat(fixture.count("PUT", "/api/v1/issues/" + ISSUE + "/transition/" + TRANSITION))
        .isEqualTo(1);
    fixture.issue.put("status", "OPEN");
    var error =
        content(call("transition_issue", Map.of("issue_key", "TAS-1", "target_status", "MISSING")))
            .path("error");
    assertThat(error.path("code").asText()).isEqualTo("invalid_argument");
    assertThat(error.path("details").path("allowed_values").toString()).contains("READY");
  }

  @Test
  void transitionRaceReportsCurrentStateWithoutReplay() {
    fixture.conflictTransition.set(true);
    var error = content(call("transition_issue", args("transition_issue"))).path("error");
    assertThat(error.path("code").asText()).isEqualTo("conflict");
    assertThat(error.path("details").path("current_version").asInt()).isEqualTo(9);
    assertThat(fixture.count("PUT", "/api/v1/issues/" + ISSUE + "/transition/" + TRANSITION))
        .isEqualTo(1);
  }

  @Test
  void labelRepeatsPerformNoDuplicateMutationAndRemoveOnlyBinding() {
    assertThat(call("add_label", args("add_label")).isError()).isFalse();
    assertThat(call("add_label", args("add_label")).isError()).isFalse();
    assertThat(call("remove_label", args("remove_label")).isError()).isFalse();
    assertThat(call("remove_label", args("remove_label")).isError()).isFalse();
    assertThat(fixture.labels).hasValue(2);
    assertThat(fixture.calls).noneMatch(request -> request.uri().equals(PP + "/labels/" + LABEL));
  }

  @Test
  void readOnlyPatCannotInvokeAnyWriteAndNoRequestReachesGateway() {
    context =
        new McpRequestContext(
            context.requestId(),
            context.bearerToken(),
            context.user().toBuilder().clearScopes().addScopes("read").build());
    names()
        .forEach(
            name ->
                assertThat(content(call(name, args(name))).path("error").path("code").asText())
                    .isEqualTo("insufficient_scope"));
    assertThat(fixture.calls).isEmpty();
  }

  @Test
  void invalidCreateUuidMissingChangesAndActualTextLimitsFailBeforeHttp() {
    for (Map<String, Object> input :
        List.of(
            Map.<String, Object>of("project_key", "TAS", "summary", "New"),
            Map.<String, Object>of(
                "project_key", "TAS", "summary", "New", "idempotency_key", "bad")))
      assertThatThrownBy(() -> call("create_issue", input)).isInstanceOf(McpError.class);
    assertThatThrownBy(() -> call("update_issue", Map.of("issue_key", "TAS-1", "version", 1)))
        .isInstanceOf(McpError.class);
    assertThatThrownBy(
            () -> call("add_comment", Map.of("issue_key", "TAS-1", "body", "x".repeat(10001))))
        .isInstanceOf(McpError.class);
    assertThatThrownBy(
            () ->
                call(
                    "log_work",
                    Map.of(
                        "issue_key",
                        "TAS-1",
                        "spent_minutes",
                        1,
                        "work_date",
                        "2026-10-20",
                        "comment",
                        "x".repeat(2001))))
        .isInstanceOf(McpError.class);
    assertThat(fixture.calls).isEmpty();
  }

  @Test
  void disconnectAfterCommentIsNotRetriedAndResponseRequiresReconciliation() {
    fixture.disconnectComment.set(true);
    var result = call("add_comment", args("add_comment"));
    assertThat(result.isError()).isTrue();
    assertThat(fixture.comments).hasValue(1);
    assertThat(content(result).path("error").path("details").path("reason").asText())
        .isEqualTo("WRITE_OUTCOME_UNKNOWN");
    assertThat(
            content(result)
                .path("error")
                .path("message")
                .asText()
                .toLowerCase(java.util.Locale.ROOT))
        .contains("не повторяйте", "автоматически");
  }

  private McpSchema.CallToolResult call(String name, Map<String, Object> input) {
    return executor
        .execute(
            tools.stream()
                .filter(tool -> name.equals(tool.definition().name()))
                .findFirst()
                .orElseThrow(),
            McpTransportContext.create(Map.of(McpRequestContext.KEY, context)),
            new McpSchema.CallToolRequest(name, input))
        .block(Duration.ofSeconds(8));
  }

  private JsonNode content(McpSchema.CallToolResult result) {
    return mapper.valueToTree(result.structuredContent());
  }

  static Map<String, Object> args(String name) {
    return switch (name) {
      case "create_issue" -> Map.of("project_key", "TAS", "summary", "New", "idempotency_key", KEY);
      case "update_issue" -> Map.of("issue_key", "TAS-1", "summary", "Changed");
      case "assign_issue" -> Map.of("issue_key", "TAS-1", "assignee", "me");
      case "transition_issue" -> Map.of("issue_key", "TAS-1", "target_status", "READY");
      case "add_comment" -> Map.of("issue_key", "TAS-1", "body", "Comment");
      case "log_work" ->
          Map.of(
              "issue_key",
              "TAS-1",
              "spent_minutes",
              60,
              "work_date",
              "2026-10-20",
              "comment",
              "Work");
      case "add_label", "remove_label" -> Map.of("issue_key", "TAS-1", "label", "bug");
      case "link_issues" ->
          Map.of("issue_key", "TAS-1", "target_issue_key", "TAS-2", "link_type", "BLOCKS");
      case "mark_notifications_read" -> Map.of("notification_id", LABEL);
      default -> Map.of("issue_key", "TAS-1");
    };
  }
}
