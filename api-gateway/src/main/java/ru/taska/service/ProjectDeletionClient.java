package ru.taska.service;

import io.grpc.Status;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.DeleteProjectRpcRequest;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ProjectDeletionServiceGrpc;
import ru.taska.api.b2b.v1.ReactorProjectDeletionServiceGrpc;
import ru.taska.config.GrpcClientConfig;
import ru.taska.domain.GatewayContext;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;

/** Подписанные вызовы первичного владельца операции удаления. */
@Component
public class ProjectDeletionClient {
  private final GrpcClientConfig config;
  private final ObjectProvider<RpcContextSecurity> security;

  public ProjectDeletionClient(
      GrpcClientConfig config, ObjectProvider<RpcContextSecurity> security) {
    this.config = config;
    this.security = security;
  }

  private SignedQuotaClient client(UUID tenant, GatewayContext context) {
    var guard = security.getIfAvailable();
    if (guard == null || context.verified() == null) throw Status.UNAVAILABLE.asRuntimeException();
    if (!tenant.toString().equals(context.verified().tenantId()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    return new SignedQuotaClient(guard, Clock.systemUTC(), "api-gateway", "project-service");
  }

  public Mono<Operation> start(
      UUID tenant,
      UUID project,
      UUID key,
      int expected,
      boolean confirmed,
      String projectKey,
      GatewayContext context) {
    return Mono.defer(
        () ->
            client(tenant, context)
                .call(
                    tenant,
                    ProjectDeletionServiceGrpc.getDeleteProjectMethod().getFullMethodName(),
                    "deleteProject",
                    new RpcContextSecurity.Policy(true, true),
                    c ->
                        DeleteProjectRpcRequest.newBuilder()
                            .setContext(c)
                            .setProjectId(project.toString())
                            .setConfirmed(confirmed)
                            .setConfirmedProjectKey(projectKey)
                            .setMutation(
                                MutationOptions.newBuilder()
                                    .setIdempotencyKey(key.toString())
                                    .setExpectedVersion(Integer.toString(expected)))
                            .build(),
                    (q, auth) ->
                        ReactorProjectDeletionServiceGrpc.newReactorStub(
                                config.projectDeletionManagedChannel())
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .deleteProject(q)));
  }

  public Mono<Operation> get(UUID tenant, UUID id, GatewayContext context) {
    return Mono.defer(
        () ->
            client(tenant, context)
                .call(
                    tenant,
                    ProjectDeletionServiceGrpc.getGetDeletionMethod().getFullMethodName(),
                    "getProjectDeletion",
                    new RpcContextSecurity.Policy(true, false),
                    c ->
                        ProjectDeletionRequest.newBuilder()
                            .setContext(c)
                            .setOperationId(id.toString())
                            .build(),
                    (q, auth) ->
                        ReactorProjectDeletionServiceGrpc.newReactorStub(
                                config.projectDeletionManagedChannel())
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .getDeletion(q)));
  }
}
