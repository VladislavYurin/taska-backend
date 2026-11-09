package ru.taska.storage.versioned;

import reactor.core.publisher.Mono;

/** Pinned private object operations; keys come only from the owning durable manifest. */
public interface VersionedObjects {
  record Measurement(String version, long bytes, String contentType, String checksumSha256) {
    public Measurement {
      if (version == null
          || version.isBlank()
          || "null".equals(version)
          || bytes <= 0
          || contentType == null
          || checksumSha256 == null
          || !checksumSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
    }
  }

  Mono<Void> requireVersioning();

  Mono<Measurement> measure(String key, String version, long maximumBytes);

  /** Delete a pinned version and verify absence; successful DELETE alone is insufficient. */
  Mono<Void> deleteVersion(String key, String version);

  /** Delete at most 100 exact-key versions/markers; false means another owned batch is required. */
  Mono<Boolean> purgeKey(String key);
}
