package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.oauth.v1.ExchangeOAuthTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;

class McpOAuthSecurityTest {
  static final String OAT = "tsk_oat_" + "B".repeat(43), DELEGATED = "fixture.delegation.jwt";
  final McpOAuthProperties oauth =
      new McpOAuthProperties(
          true,
          URI.create("https://auth.example.test"),
          URI.create("https://mcp.example.test/mcp"),
          "fixture-mcp-exchange-credential-32chars");

  @Test
  void challengeAndMetadataHaveCanonicalResourceIndependentOfHost() {
    var filter =
        new McpAuthenticationFilter(
            (token, id) -> Mono.error(new AssertionError()),
            properties(),
            oauth,
            (token, id) -> Mono.error(new AssertionError()));
    var request =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("https://evil.example/mcp").header("Host", "evil.example"));
    filter.filter(request, e -> Mono.error(new AssertionError())).block();
    assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(request.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .contains(
            "resource_metadata=\"https://mcp.example.test/.well-known/oauth-protected-resource\"",
            "scope=\"read\"")
        .doesNotContain("evil");
    var metadata = new OAuthProtectedResourceMetadata(oauth).metadata();
    assertThat(metadata.get("resource")).isEqualTo("https://mcp.example.test/mcp");
    assertThat(metadata.get("authorization_servers"))
        .isEqualTo(List.of("https://auth.example.test"));
  }

  @Test
  void opaqueIsExchangedPerRequestAndOnlyDelegationReachesHandler() {
    McpOAuthAuthenticator auth = mock(McpOAuthAuthenticator.class);
    var user =
        McpAuthenticationFilterTest.user().toBuilder()
            .setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_OAUTH)
            .build();
    when(auth.authenticate(eq(OAT), anyString()))
        .thenReturn(
            Mono.just(
                ExchangeOAuthTokenResponse.newBuilder()
                    .setUserContext(user)
                    .setDelegationToken(DELEGATED)
                    .build()),
            Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException()));
    var filter =
        new McpAuthenticationFilter(
            (token, id) -> Mono.error(new AssertionError()), properties(), oauth, auth);
    var first = exchange(OAT);
    filter
        .filter(
            first,
            e -> {
              var context = (McpRequestContext) e.getAttribute(McpRequestContext.KEY);
              assertThat(context.bearerToken()).isEqualTo(DELEGATED);
              assertThat(context.toString()).doesNotContain(OAT, DELEGATED);
              assertThat(context.withMetadata(McpCallMetadata.NONE).authorizationKind())
                  .isEqualTo(McpAuthorizationKind.OAUTH);
              return e.getResponse().setComplete();
            })
        .block();
    var second = exchange(OAT);
    filter.filter(second, e -> Mono.error(new AssertionError())).block();
    assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(auth, times(2)).authenticate(eq(OAT), anyString());
  }

  @Test
  void externalJwtAndGatewayDelegationCannotAuthenticateAtMcp() {
    McpOAuthAuthenticator auth = mock(McpOAuthAuthenticator.class);
    PatAuthenticator pat = mock(PatAuthenticator.class);
    var filter = new McpAuthenticationFilter(pat, properties(), oauth, auth);
    for (String invalid : List.of(DELEGATED, "eyJ.external.jwt", "tsk_ort_" + "A".repeat(43))) {
      var exchange = exchange(invalid);
      filter.filter(exchange, e -> Mono.error(new AssertionError())).block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    verifyNoInteractions(auth, pat);
  }

  private MockServerWebExchange exchange(String token) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.post("/mcp").header("Authorization", "Bearer " + token));
  }

  private McpProperties properties() {
    return McpAuthenticationFilterTest.properties(
        URI.create("http://localhost:1"), Duration.ofSeconds(1), 2097152);
  }
}
