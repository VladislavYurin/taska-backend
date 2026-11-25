package ru.taska.projectbootstrap;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.ProjectInitializationServiceGrpc;
import ru.taska.projectstorage.ProjectCreation;
import ru.taska.security.tenant.*;

/** Explicit operator worklist only. Recovery never has new-resource admission authority. */
@Component
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public final class ProjectCreationRecovery {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ProjectCreationRecovery.class);
  private final ProjectCreation creation;
  private final ManagedServiceContexts contexts;
  private final List<UUID> tenants;
  private final AtomicBoolean running = new AtomicBoolean();

  public ProjectCreationRecovery(
      ProjectCreation creation,
      ManagedServiceContexts contexts,
      @Value("${taska.projects.recovery-tenants:}") String configured) {
    this.creation = creation;
    this.contexts = contexts;
    tenants =
        configured.isBlank()
            ? List.of()
            : Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(UUID::fromString)
                .distinct()
                .toList();
    log.debug("Project recovery configured for {} company scopes", tenants.size());
    if (tenants.size() > 100)
      throw new IllegalArgumentException("Bounded project recovery worklist required");
  }

  private <T> Mono<T> authorized(UUID tenant, java.util.function.Supplier<Mono<T>> work) {
    return contexts
        .authorize(
            "provisioning",
            "project-service",
            ProjectInitializationServiceGrpc.getRecoverMethod().getFullMethodName(),
            "recoverProjectCreation",
            tenant,
            Set.of("write", "project:recover"),
            new RpcContextSecurity.Policy(true, true))
        .flatMap(c -> RpcContextBridge.install(Mono.defer(work), c));
  }

  private static String code(Throwable error) {
    return error instanceof ru.taska.exception.DomainException d
        ? d.getStatus().name()
        : error instanceof io.grpc.StatusRuntimeException e
            ? e.getStatus().getCode().name()
            : error.getClass().getSimpleName();
  }

  @Scheduled(fixedDelayString = "${taska.projects.recovery-interval:30s}")
  public void tick() {
    if (tenants.isEmpty() || !running.compareAndSet(false, true)) return;
    Flux.fromIterable(tenants)
        .concatMap(
            tenant ->
                authorized(tenant, creation::recoveryWorklist)
                    .flatMapMany(
                        ids -> {
                          log.debug("Project recovery selected {} operations", ids.size());
                          return Flux.fromIterable(ids)
                              .concatMap(
                                  op ->
                                      authorized(tenant, () -> creation.recover(op))
                                          .onErrorResume(
                                              error -> {
                                                log.debug(
                                                    "Project continuation deferred: {}",
                                                    code(error));
                                                return Mono.empty();
                                              }));
                        })
                    .onErrorResume(
                        error -> {
                          log.debug("Project recovery worklist deferred: {}", code(error));
                          return Flux.empty();
                        }))
        .doFinally(signal -> running.set(false))
        .subscribe();
  }
}
