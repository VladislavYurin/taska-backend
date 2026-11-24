package ru.taska.storage.versioned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3UploadObjectsTest {
  @Test
  @Timeout(20)
  void sdkDoesNotRetryCopyEvenWhenClientNormallyRetriesTransientErrors() throws Exception {
    var copies = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            if (exchange.getRequestMethod().equals("HEAD")) {
              exchange.sendResponseHeaders(404, -1);
            } else {
              copies.incrementAndGet();
              byte[] error =
                  "<Error><Code>ServiceUnavailable</Code><Message>owned transient failure</Message></Error>"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/xml");
              exchange.sendResponseHeaders(503, error.length);
              exchange.getResponseBody().write(error);
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var client =
        S3AsyncClient.builder()
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("owned", "owned-secret")))
            .overrideConfiguration(
                c ->
                    c.retryPolicy(
                        software.amazon.awssdk.core.retry.RetryPolicy.builder()
                            .numRetries(3)
                            .build()))
            .build()) {
      var versions = mock(VersionedObjects.class);
      when(versions.requireVersioning()).thenReturn(Mono.empty());
      var objects = new S3UploadObjects(client, null, versions, "owned-bucket", Clock.systemUTC());
      assertThrows(
          S3Exception.class,
          () ->
              objects
                  .copyPinned(
                      "owned/staging",
                      "pinned-version",
                      "owned/final",
                      "owned-intent",
                      "text/plain")
                  .block(Duration.ofSeconds(10)));
      assertEquals(1, copies.get());
    } finally {
      server.stop(0);
    }
  }
}
