package ru.taska.storage.versioned;

import reactor.core.publisher.Mono;

/** Only immutable server-selected versions. The maximum read is one MiB. */
public interface AttachmentContentObjects {
  int MAX_CHUNK = 1024 * 1024;

  Mono<Void> head(String key, String version, long total);

  Mono<byte[]> read(String key, String version, long total, long offset, int length);
}
