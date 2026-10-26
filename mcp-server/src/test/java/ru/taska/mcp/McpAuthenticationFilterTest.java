package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;

class McpAuthenticationFilterTest {
  static final String TOKEN = "tsk_pat_" + "a".repeat(43);

  static UserContext user() {
    return UserContext.newBuilder()
        .setUserId("user-1")
        .setTokenId("token-1")
        .setStatus(UserStatus.USER_STATUS_ACTIVE)
        .setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_PAT)
        .addScopes("read")
        .setTokenExpiresAt(
            Timestamp.newBuilder().setSeconds(Instant.now().plusSeconds(3600).getEpochSecond()))
        .build();
  }

  static McpProperties properties(URI uri, Duration timeout, int cap) {
    return new McpProperties(
        uri,
        Duration.ofSeconds(1),
        timeout,
        cap,
        List.of("http://localhost:6274"),
        new McpProperties.Auth("localhost", 1, Duration.ofSeconds(1)));
  }

  @Test
  void validatesEveryRequestAndRejectsRevocationWithoutPositiveCache() {
    AtomicInteger calls = new AtomicInteger();
    var filter =
        filter(
            (token, requestId) ->
                calls.incrementAndGet() == 1
                    ? Mono.just(user())
                    : Mono.error(Status.UNAUTHENTICATED.asRuntimeException()));
    var first = exchange(TOKEN);
    filter
        .filter(
            first,
            request -> {
              var context = (McpRequestContext) request.getAttributes().get(McpRequestContext.KEY);
              assertThat(context.bearerToken()).isEqualTo(TOKEN);
              assertThat(context.toString()).doesNotContain(TOKEN);
              request.getResponse().setStatusCode(HttpStatus.OK);
              return request.getResponse().setComplete();
            })
        .block();
    assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    var second = exchange(TOKEN);
    filter.filter(second, request -> Mono.error(new AssertionError("must not dispatch"))).block();
    assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void missingMalformedAndJwtCredentialsDoNotCallAuth() {
    var filter = filter((token, id) -> Mono.error(new AssertionError("must not call auth")));
    for (String token : List.of("", "eyJ.jwt.value", TOKEN + "x")) {
      var exchange = exchange(token);
      filter
          .filter(exchange, request -> Mono.error(new AssertionError("must not dispatch")))
          .block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
          .startsWith("Bearer");
    }
  }

  @Test
  void authenticatesMatrixParametersAndEncodedPathsJustLikeRouter() {
    var filter = filter((token, id) -> Mono.error(new AssertionError("must not call auth")));
    for (String path : List.of("/mcp;parameter=value", "/m%63p")) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
      filter
          .filter(exchange, request -> Mono.error(new AssertionError("must not dispatch")))
          .block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
  }

  @Test
  void rejectsInactiveExpiredAndWrongTokenKind() {
    for (UserContext user :
        List.of(
            user().toBuilder().setStatus(UserStatus.USER_STATUS_LOCKED).build(),
            user().toBuilder().setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_JWT).build(),
            user().toBuilder().setTokenExpiresAt(Timestamp.newBuilder().setSeconds(1)).build())) {
      var exchange = exchange(TOKEN);
      filter((token, id) -> Mono.just(user))
          .filter(exchange, request -> Mono.error(new AssertionError("must not dispatch")))
          .block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
  }

  @Test
  void rejectsForeignOriginBeforeAuthAndAllowsExactPreflight() {
    var filter = filter((token, id) -> Mono.error(new AssertionError("must not call auth")));
    var foreign =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/mcp")
                .header(HttpHeaders.ORIGIN, "http://localhost:6274.evil.test"));
    filter.filter(foreign, request -> Mono.empty()).block();
    assertThat(foreign.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var preflight =
        MockServerWebExchange.from(
            MockServerHttpRequest.options("/mcp")
                .header(HttpHeaders.ORIGIN, "http://localhost:6274")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"));
    filter
        .filter(preflight, request -> Mono.error(new AssertionError("must not dispatch")))
        .block();
    assertThat(preflight.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(preflight.getResponse().getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("http://localhost:6274");
  }

  @Test
  void scopeFailureIs403AndAuthOutageIs503() {
    var scope = exchange(TOKEN);
    filter((token, id) -> Mono.just(user().toBuilder().clearScopes().build()))
        .filter(scope, request -> Mono.error(new AssertionError("must not dispatch")))
        .block();
    assertThat(scope.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var outage = exchange(TOKEN);
    filter((token, id) -> Mono.error(Status.UNAVAILABLE.asRuntimeException()))
        .filter(outage, request -> Mono.empty())
        .block();
    assertThat(outage.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void rejectsUnsupportedHeaderAndKeepsDownstreamProtocolErrorsOutOfAuthHandling() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("MCP-Protocol-Version", "2099-01-01"));
    filter((token, id) -> Mono.just(user()))
        .filter(exchange, request -> Mono.error(new AssertionError("must not dispatch")))
        .block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    reactor.test.StepVerifier.create(
            filter((token, id) -> Mono.just(user()))
                .filter(
                    exchange(TOKEN),
                    request -> Mono.error(new IllegalStateException("protocol failure"))))
        .expectErrorMessage("protocol failure")
        .verify();
  }

  private static McpAuthenticationFilter filter(PatAuthenticator authenticator) {
    return new McpAuthenticationFilter(
        authenticator,
        properties(URI.create("http://localhost:8080"), Duration.ofSeconds(1), 1024));
  }

  private static MockServerWebExchange exchange(String token) {
    var request = MockServerHttpRequest.post("/mcp");
    if (!token.isEmpty()) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return MockServerWebExchange.from(request);
  }
}
