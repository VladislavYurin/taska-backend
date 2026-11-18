package ru.taska.storage.versioned;

import java.time.Instant;
import reactor.core.publisher.Mono;

/** Только ключи сохранённого server-side manifest; клиент не задаёт destination. */
public interface UploadObjects {
  record Capability(String url) {
    @Override
    public String toString() {
      return "Capability[redacted]";
    }
  }

  Mono<Capability> presign(String stagingKey, String mime, long bytes, Instant until);

  Mono<String> stagingVersion(String stagingKey, String mime, long bytes);

  Mono<String> existingFinal(String finalKey, String stagingVersion, String intent);

  Mono<String> copyPinned(
      String stagingKey, String stagingVersion, String finalKey, String intent, String mime);
}
