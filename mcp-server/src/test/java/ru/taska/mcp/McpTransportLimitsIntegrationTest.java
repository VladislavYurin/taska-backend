package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {McpServerApplication.class, McpTransportLimitsIntegrationTest.ProbeConfig.class})
class McpTransportLimitsIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean PatAuthenticator authenticator;
  @Autowired AtomicInteger writes;

  @BeforeEach
  void setup() {
    writes.set(0);
    when(authenticator.authenticate(anyString(), anyString()))
        .thenReturn(
            Mono.just(
                McpAuthenticationFilterTest.user().toBuilder()
                    .setUserId(McpToolPolicyTest.USER)
                    .setTokenId(McpToolPolicyTest.TOKEN_ID)
                    .build()));
  }

  @Test
  void scopeChallengeHappensAtHttpBeforeWriteHandlerAndSchemaErrorsRemainSdkErrors() {
    post("/%6Dcp", call("{\"body\":\"private text\"}"))
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .valueEquals(
            HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"write\"");
    assertThat(writes).hasValue(0);
    post("/mcp", call("{\"body\":13}"))
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("-32602"));
    assertThat(writes).hasValue(0);
    when(authenticator.authenticate(anyString(), anyString()))
        .thenReturn(
            Mono.just(
                McpAuthenticationFilterTest.user().toBuilder()
                    .setUserId(McpToolPolicyTest.USER)
                    .setTokenId(McpToolPolicyTest.TOKEN_ID)
                    .addScopes("write")
                    .build()));
    post("/mcp", call("{\"body\":\"private text\"}"))
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("\"isError\":false"));
    assertThat(writes).hasValue(1);
  }

  @Test
  void oversizedHttpBodyIsRejectedBeforeAnyToolInvocation() {
    post("/mcp", "x".repeat(65537)).expectStatus().isEqualTo(413);
    assertThat(writes).hasValue(0);
  }

  private WebTestClient.ResponseSpec post(String path, String body) {
    return WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .post()
        .uri(java.net.URI.create("http://localhost:" + port + path))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
        .header("MCP-Protocol-Version", "2025-11-25")
        .header("X-Request-Id", "http-limits")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .bodyValue(body)
        .exchange();
  }

  private static String call(String args) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"write_probe\",\"arguments\":"
        + args
        + "}}";
  }

  @TestConfiguration
  static class ProbeConfig {
    @Bean
    AtomicInteger writes() {
      return new AtomicInteger();
    }

    @Bean
    ToolContributor writeProbe(AtomicInteger writes) {
      var registration =
          McpToolPolicyTest.registration(
              true,
              call -> {
                writes.incrementAndGet();
                return Mono.just(
                    new ToolData(McpToolPolicyTest.JSON.createObjectNode().put("ok", true), "ok"));
              });
      var original = registration.definition();
      var schema =
          java.util.Map.<String, Object>of(
              "type",
              "object",
              "properties",
              java.util.Map.of("body", java.util.Map.of("type", "string")),
              "required",
              List.of("body"),
              "additionalProperties",
              false);
      var definition =
          new ToolDefinition(
              original.name(),
              original.description(),
              original.scope(),
              original.readOnly(),
              original.idempotent(),
              schema,
              original.outputSchema());
      return () -> List.of(new ToolRegistration(definition, registration.handler()));
    }
  }
}
