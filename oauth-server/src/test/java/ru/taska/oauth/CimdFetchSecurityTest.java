package ru.taska.oauth;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

class CimdFetchSecurityTest {
  DisposableServer server;
  final AtomicInteger redirected = new AtomicInteger();

  @BeforeEach
  void start() {
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(
                routes ->
                    routes
                        .get(
                            "/redirect",
                            (request, response) ->
                                response
                                    .status(302)
                                    .header(
                                        "Location", "http://127.0.0.1:" + server.port() + "/secret")
                                    .send())
                        .get(
                            "/secret",
                            (request, response) -> {
                              redirected.incrementAndGet();
                              return response
                                  .header("Content-Type", "application/json")
                                  .sendString(Mono.just("{}"));
                            })
                        .get(
                            "/large",
                            (request, response) ->
                                response
                                    .header("Content-Type", "application/json")
                                    .sendString(
                                        Flux.just("a".repeat(32768), "b".repeat(32768), "c")))
                        .get(
                            "/valid",
                            (request, response) ->
                                response
                                    .header("Content-Type", "application/json")
                                    .header("Cache-Control", "max-age=60")
                                    .sendString(Mono.just("{}")))
                        .get(
                            "/no-store",
                            (request, response) ->
                                response
                                    .header("Content-Type", "application/json")
                                    .header("Cache-Control", "no-store")
                                    .sendString(Mono.just("{}")))
                        .get(
                            "/not-json",
                            (request, response) ->
                                response
                                    .header("Content-Type", "text/html")
                                    .sendString(Mono.just("{}"))))
            .bindNow();
  }

  @AfterEach
  void stop() {
    server.disposeNow();
  }

  @Test
  void redirectsAreNotFollowedAndNonJsonIsRejected() {
    var fetcher = fetcher();
    assertThatThrownBy(() -> fetcher.fetch(url("/redirect")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(redirected).hasValue(0);
    assertThatThrownBy(() -> fetcher.fetch(url("/not-json")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chunkedResponseIsBoundedBeforeJsonParsingAndHttpCacheCannotExtendTtl() {
    var fetcher = fetcher();
    assertThatThrownBy(() -> fetcher.fetch(url("/large")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
    assertThat(fetcher.fetch(url("/valid")).cacheFor()).isEqualTo(Duration.ofMinutes(1));
    assertThat(fetcher.fetch(url("/no-store")).cacheFor()).isEqualTo(Duration.ZERO);
  }

  private URI url(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private PublicCimdDocumentFetcher fetcher() {
    var properties =
        new OAuthServerProperties(
            URI.create("https://auth.example.test"),
            URI.create("https://mcp.example.test/mcp"),
            List.of(),
            List.of(),
            false,
            1000,
            Duration.ofMinutes(5),
            "auth-service",
            "fixture-introspection-secret-32chars",
            new OAuthServerProperties.Auth(
                "localhost", 1, Duration.ofSeconds(2), "fixture-identity-service-secret-32"));
    // The production resolver is tested separately; only this transport fixture reaches loopback.
    return new PublicCimdDocumentFetcher(properties, HttpClient.create());
  }
}
