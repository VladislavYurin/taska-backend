package ru.taska.issuestorage;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Политика обязана подтвердить текущую identity/ACL, бизнес-флаги и frozen accepted intent. */
@FunctionalInterface
public interface AttachmentUploadPolicy {
  record Grant(boolean bypassLimit, long maximumAttachmentBytes, long policyVersion, Duration ttl) {
    public Grant {
      if (maximumAttachmentBytes <= 0 || policyVersion <= 0 || ttl.isNegative() || ttl.isZero())
        throw new IllegalArgumentException();
    }
  }

  Mono<Grant> check(UUID project, UUID issue, AttachmentUploadStore.Upload upload, String phase);
}
