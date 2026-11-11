package ru.taska.capability;

import java.time.Clock;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.*;

/** Dependent services never infer project access from a tenant or account role. */
public final class ProjectAccessClient {
  private final ManagedDomainReads reads;
  private final SignedQuotaClient client;

  public ProjectAccessClient(ManagedDomainReads reads, RpcContextSecurity security, String issuer) {
    this.reads = reads;
    client = new SignedQuotaClient(security, Clock.systemUTC(), issuer, "project-service");
  }

  public Mono<CheckProjectMemberRoleResponse> check(UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent ->
                client.call(
                    UUID.fromString(parent.tenantId()),
                    ProjectServiceGrpc.getCheckProjectMemberRoleMethod().getFullMethodName(),
                    "checkProjectMemberRole",
                    c ->
                        CheckProjectMemberRoleRequest.newBuilder()
                            .setContext(c)
                            .setHeader(c.getHeader())
                            .setBody(
                                CheckProjectMemberRoleRequestBody.newBuilder()
                                    .setProjectId(project.toString())
                                    .setUserId(parent.actorId()))
                            .build(),
                    (q, auth) ->
                        ReactorProjectServiceGrpc.newReactorStub(reads.channel("project-service"))
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .checkProjectMemberRole(q)))
        .map(
            r -> {
              if (!r.getProjectExists()
                  || !r.getIsMember()
                  || r.getRole() == ProjectRole.PROJECT_ROLE_UNSPECIFIED
                  || r.getRole() == ProjectRole.UNRECOGNIZED)
                throw new DomainException(DomainStatus.NOT_FOUND, "Project not found");
              return r;
            });
  }

  public Mono<ListProjectIssueTypesResponse> types(UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent ->
                client.call(
                    UUID.fromString(parent.tenantId()),
                    ProjectServiceGrpc.getListProjectIssueTypesMethod().getFullMethodName(),
                    "listProjectIssueTypes",
                    c ->
                        ProjectIssueTypesRequest.newBuilder()
                            .setContext(c)
                            .setHeader(c.getHeader())
                            .setProjectId(project.toString())
                            .setActorUserId(parent.actorId())
                            .build(),
                    (q, auth) ->
                        ReactorProjectServiceGrpc.newReactorStub(reads.channel("project-service"))
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .listProjectIssueTypes(q)));
  }
}
