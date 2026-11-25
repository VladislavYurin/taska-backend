package ru.taska.projectbootstrap;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ProjectDeletionServiceGrpc;
import ru.taska.projectstorage.ProjectDeletions;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;

/** Ограниченная операторская worklist; каждая попытка получает свежую SERVICE authority. */
@Component
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public final class ProjectDeletionRecovery {
  private final ProjectDeletions deletions;
  private final ManagedServiceContexts contexts;
  private final List<UUID> tenants;
  private final AtomicBoolean running = new AtomicBoolean();

  public ProjectDeletionRecovery(
      ProjectDeletions deletions,
      ManagedServiceContexts contexts,
      @Value("${taska.projects.deletion-tenants:}") String configured) {
    this.deletions = deletions;
    this.contexts = contexts;
    tenants =
        configured.isBlank()
            ? List.of()
            : Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(UUID::fromString)
                .distinct()
                .toList();
    if (tenants.size() > 100)
      throw new IllegalArgumentException("Требуется ограниченная worklist удаления проектов");
  }

  private <T> Mono<T> authorized(UUID t, java.util.function.Supplier<Mono<T>> work) {
    return contexts
        .authorize(
            "PROJECT_CLEANUP",
            "project-service",
            ProjectDeletionServiceGrpc.getRecoverDeletionMethod().getFullMethodName(),
            "recoverProjectDeletion",
            t,
            Set.of("write", "project:delete"),
            new RpcContextSecurity.Policy(true, true))
        .flatMap(c -> RpcContextBridge.install(Mono.defer(work), c));
  }

  @Scheduled(fixedDelayString = "${taska.projects.deletion-interval:30s}")
  public void tick() {
    if (tenants.isEmpty() || !running.compareAndSet(false, true)) return;
    Flux.fromIterable(tenants)
        .concatMap(
            t ->
                authorized(t, deletions::pending)
                    .flatMapMany(
                        ids ->
                            Flux.fromIterable(ids)
                                .concatMap(
                                    id ->
                                        authorized(t, () -> deletions.recover(id))
                                            .onErrorResume(e -> Mono.empty())))
                    .onErrorResume(e -> Flux.empty()))
        .doFinally(s -> running.set(false))
        .subscribe();
  }
}
