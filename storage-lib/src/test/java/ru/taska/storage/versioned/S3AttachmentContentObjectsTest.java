package ru.taska.storage.versioned;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.taska.exception.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class S3AttachmentContentObjectsTest {
  @Test
  void pinsVersionAndValidatesExactRange() throws Exception {
    exercise(false, false);
  }

  @Test
  void rejectsBackendIgnoringRange() throws Exception {
    exercise(true, false);
  }

  @Test
  void rejectsChangedVersion() throws Exception {
    exercise(false, true);
  }

  void exercise(boolean ignore, boolean change) throws Exception {
    var range = new AtomicReference<String>();
    var query = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          try {
            range.set(e.getRequestHeaders().getFirst("Range"));
            query.set(e.getRequestURI().getRawQuery());
            e.getResponseHeaders().set("x-amz-version-id", change ? "other" : "pinned");
            if (!ignore) e.getResponseHeaders().set("Content-Range", "bytes 2-5/10");
            byte[] body = ignore ? new byte[100] : new byte[] {2, 3, 4, 5};
            e.sendResponseHeaders(ignore ? 200 : 206, body.length);
            e.getResponseBody().write(body);
          } finally {
            e.close();
          }
        });
    server.start();
    try (var s3 =
        S3AsyncClient.builder()
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture", "fixture")))
            .build()) {
      var objects = new S3AttachmentContentObjects(s3, "owned");
      if (ignore || change) {
        var failure =
            assertThrows(
                DomainException.class,
                () ->
                    objects
                        .read("tenant/owned/final", "pinned", 10, 2, 4)
                        .block(Duration.ofSeconds(8)));
        assertEquals(DomainStatus.DATA_LOSS, failure.getStatus());
      } else
        assertArrayEquals(
            new byte[] {2, 3, 4, 5},
            objects.read("tenant/owned/final", "pinned", 10, 2, 4).block(Duration.ofSeconds(8)));
      assertEquals("bytes=2-5", range.get());
      assertTrue(query.get().contains("versionId=pinned"));
      assertThrows(
          DomainException.class,
          () ->
              objects
                  .read(
                      "tenant/owned/final", "pinned", 10, 0, AttachmentContentObjects.MAX_CHUNK + 1)
                  .block());
    } finally {
      server.stop(0);
    }
  }
}
