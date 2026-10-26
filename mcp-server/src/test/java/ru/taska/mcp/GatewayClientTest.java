package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

class GatewayClientTest {
  private DisposableServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.disposeNow();
    }
  }

  @Test
  void forwardsOnlyCredentialsAndRequestIdToFixedGatewayPath() {
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> id = new AtomicReference<>();
    server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) -> {
                  assertThat(request.uri()).isEqualTo("/api/v1/users/me");
                  auth.set(request.requestHeaders().get(HttpHeaders.AUTHORIZATION));
                  id.set(request.requestHeaders().get("X-Request-Id"));
                  return response
                      .header(HttpHeaders.CONTENT_TYPE, "application/json")
                      .sendString(Mono.just("{\"id\":\"user-1\",\"avatarUnavailable\":false}"));
                })
            .bindNow();
    assertThat(
            client(Duration.ofSeconds(2), 1024).currentUser(context()).block().get("id").asText())
        .isEqualTo("user-1");
    assertThat(auth.get()).isEqualTo("Bearer " + McpAuthenticationFilterTest.TOKEN);
    assertThat(id.get()).isEqualTo("request-test");
  }

  @Test
  void mapsStatusWithoutLeakingUpstreamBodyAndDoesNotFollowRedirects() {
    for (int status : new int[] {400, 401, 403, 404, 409, 429, 500, 302}) {
      server =
          HttpServer.create()
              .port(0)
              .handle(
                  (request, response) ->
                      response
                          .status(status)
                          .header(HttpHeaders.LOCATION, "http://example.invalid/secret")
                          .header(HttpHeaders.RETRY_AFTER, "5")
                          .sendString(Mono.just("private sql and secret")))
              .bindNow();
      StepVerifier.create(client(Duration.ofSeconds(2), 1024).currentUser(context()))
          .expectErrorSatisfies(
              error -> {
                assertThat(error).isInstanceOf(GatewayToolException.class);
                assertThat(error.getMessage()).doesNotContain("private", "sql", "secret");
                if (status == 429) {
                  assertThat(((GatewayToolException) error).details())
                      .containsEntry("retry_after_seconds", 5);
                }
              })
          .verify();
      server.disposeNow();
    }
  }

  @Test
  void subscriberCancellationCancelsGatewayRequest() throws InterruptedException {
    var started = new java.util.concurrent.CountDownLatch(1);
    var closed = new java.util.concurrent.CountDownLatch(1);
    server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) -> {
                  response.withConnection(connection -> connection.onDispose(closed::countDown));
                  started.countDown();
                  return response
                      .header(HttpHeaders.CONTENT_TYPE, "application/json")
                      .sendString(Mono.delay(Duration.ofSeconds(10)).map(n -> "{}"));
                })
            .bindNow();
    var request = client(Duration.ofSeconds(15), 1024).currentUser(context()).subscribe();
    try {
      assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      request.dispose();
    }
    assertThat(closed.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void rejectsEmptyInvalidWrongContentTypeAndOversizedResponses() {
    for (String body :
        new String[] {"", "not json", "[]", "{\"data\":\"" + "x".repeat(3000) + "\"}"}) {
      server =
          HttpServer.create()
              .port(0)
              .handle(
                  (request, response) ->
                      response
                          .header(HttpHeaders.CONTENT_TYPE, "application/json")
                          .sendString(Mono.just(body)))
              .bindNow();
      StepVerifier.create(client(Duration.ofSeconds(2), 1024).currentUser(context()))
          .expectError(GatewayToolException.class)
          .verify();
      server.disposeNow();
    }
    server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) ->
                    response
                        .header(HttpHeaders.CONTENT_TYPE, "text/html")
                        .sendString(Mono.just("{}")))
            .bindNow();
    StepVerifier.create(client(Duration.ofSeconds(2), 1024).currentUser(context()))
        .expectError(GatewayToolException.class)
        .verify();
  }

  @Test
  void timeoutBecomesToolErrorAndCancellationClosesUpstreamRequest() {
    AtomicBoolean cancelled = new AtomicBoolean();
    server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) -> {
                  response.withConnection(
                      connection -> connection.onDispose(() -> cancelled.set(true)));
                  return response
                      .header(HttpHeaders.CONTENT_TYPE, "application/json")
                      .sendString(Mono.delay(Duration.ofSeconds(5)).map(n -> "{}"));
                })
            .bindNow();
    StepVerifier.create(client(Duration.ofMillis(100), 1024).currentUser(context()))
        .expectError(GatewayToolException.class)
        .verify();
    long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!cancelled.get() && System.nanoTime() < until) {
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
    assertThat(cancelled).isTrue();
    assertThat(client(Duration.ofMillis(100), 1024).healthy().block()).isFalse();
  }

  private GatewayClient client(Duration timeout, int cap) {
    return new GatewayClient(
        WebClient.builder(),
        McpAuthenticationFilterTest.properties(
            URI.create("http://localhost:" + server.port()), timeout, cap));
  }

  static McpRequestContext context() {
    return new McpRequestContext(
        "request-test", McpAuthenticationFilterTest.TOKEN, McpAuthenticationFilterTest.user());
  }
}
