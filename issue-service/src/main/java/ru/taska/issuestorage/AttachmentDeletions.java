package ru.taska.issuestorage;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.*;
import ru.taska.quota.StorageQuotaProtocol.Authority;

/** Retained intent + fenced bounded attempts. A failed attempt never releases bytes by itself. */
public final class AttachmentDeletions {
  public record Result(int processed, int completed, int failed, int dataLoss, String next) {}

  private final AttachmentLifecycleStore store;
  private final StorageQuotaOwner owner;
  private final Authority root;

  public AttachmentDeletions(
      AttachmentLifecycleStore store, StorageQuotaOwner owner, Authority root) {
    this.store = store;
    this.owner = owner;
    this.root = root;
  }

  public Mono<Result> reconcile(UUID after, int limit) {
    return recovery()
        .then(store.due(after, limit))
        .flatMap(
            ids ->
                Flux.fromIterable(ids)
                    .concatMap(this::process)
                    .collectList()
                    .map(
                        results ->
                            new Result(
                                results.size(),
                                (int) results.stream().filter(n -> n == 0).count(),
                                (int) results.stream().filter(n -> n == 1 || n == 2).count(),
                                (int) results.stream().filter(n -> n == 2).count(),
                                ids.size() == limit ? ids.getLast().toString() : "")));
  }

  public Mono<Integer> process(UUID id) {
    return recovery()
        .then(store.claim(id))
        .flatMap(
            claim ->
                owner
                    .deleteConfirmed(
                        claim.item().reservation(), StorageQuotaOwner.Use.DIRECT_DELETE)
                    .onErrorMap(e -> new AttemptFailure("PURGE", e))
                    .flatMap(
                        receipt ->
                            root.releaseUsage(receipt.reservation())
                                .onErrorMap(e -> new AttemptFailure("RELEASE", e)))
                    .timeout(Duration.ofSeconds(20))
                    .then(store.finish(claim, null))
                    .map(done -> done ? 0 : 1)
                    .onErrorResume(
                        e -> store.finish(claim, failureCode(e)).thenReturn(integrity(e) ? 2 : 1)))
        .defaultIfEmpty(3);
  }

  private static final class AttemptFailure extends RuntimeException {
    private final String phase;

    AttemptFailure(String phase, Throwable cause) {
      super(null, cause);
      this.phase = phase;
    }
  }

  /** Only bounded protocol codes are retained; never exception text, keys or credentials. */
  static String failureCode(Throwable e) {
    if (integrity(e)) return "DATA_LOSS";
    String phase = e instanceof AttemptFailure a ? a.phase : "ATTEMPT";
    Throwable cause = e instanceof AttemptFailure ? e.getCause() : e;
    String code =
        cause instanceof DomainException d
            ? d.getStatus().name()
            : io.grpc.Status.fromThrowable(cause).getCode().name();
    if (cause instanceof java.util.concurrent.TimeoutException) code = "TIMEOUT";
    String message =
        cause instanceof io.grpc.StatusRuntimeException g
            ? g.getStatus().getDescription()
            : cause.getMessage();
    for (String known :
        java.util.Set.of(
            "PUT_WINDOW_STILL_OPEN",
            "OWNED_OBJECT_CLEANUP_PENDING",
            "BOUND_STORAGE_RECOVERY_REQUIRED",
            "ACCEPTED_DELETION_REQUIRED",
            "ORIGINAL_ACTOR_REQUIRED",
            "ROOT_COMMIT_REQUIRED",
            "STORAGE_POLICY_UNAVAILABLE",
            "UPLOAD_INTENT_MISMATCH",
            "DELETE_INTENT_MISSING"))
      if (known.equals(message)) {
        code = known;
        break;
      }
    return phase + "_" + code;
  }

  private static Mono<Void> recovery() {
    return AttachmentLifecyclePolicy.service()
        .then(ru.taska.security.tenant.VerifiedTenantContext.current())
        .flatMap(
            c -> {
              var claims = ru.taska.security.tenant.RpcContextBridge.details(c).getContext();
              if (!claims.getIssuerService().equals("issue-service")
                  || !c.action().equals("reconcileAttachmentDeletions"))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "BOUND_STORAGE_RECOVERY_REQUIRED"));
              return Mono.empty();
            });
  }

  private static boolean integrity(Throwable e) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c instanceof DomainException d && d.getStatus() == DomainStatus.DATA_LOSS) return true;
      if (c instanceof io.grpc.StatusRuntimeException g
          && g.getStatus().getCode() == io.grpc.Status.Code.DATA_LOSS) return true;
    }
    return false;
  }
}
