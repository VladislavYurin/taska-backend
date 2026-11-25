package ru.taska.issuebootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.ProjectLabelResponse;
import ru.taska.capability.*;
import ru.taska.quota.ProjectQuotaWire;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

@GrpcService
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class ProjectLabelsRpc extends ReactorProjectLabelsServiceGrpc.ProjectLabelsServiceImplBase {
  private final TenantTransactionExecutor tx;
  private final ProjectAccessClient access;

  public ProjectLabelsRpc(
      ConnectionFactory factory, ManagedDomainReads reads, RpcContextSecurity security) {
    tx = new TenantTransactionExecutor(factory);
    access = new ProjectAccessClient(reads, security, "issue-service");
  }

  @Override
  public Mono<ProjectLabelFacets> read(Mono<ProjectFacetRequest> input) {
    return RpcContextBridge.inbound(
            input.flatMap(
                q -> {
                  var project = ProjectQuotaWire.uuid(q.getProjectId());
                  return access
                      .check(project)
                      .then(VerifiedTenantContext.current())
                      .flatMap(
                          c ->
                              tx.execute(
                                  db ->
                                      db.sql(
                                              "SELECT id,name,color FROM taska.project_labels WHERE"
                                                  + " tenant_id=:t AND project_id=:p AND deleted_at"
                                                  + " IS NULL ORDER BY name,id")
                                          .bind("t", UUID.fromString(c.tenantId()))
                                          .bind("p", project)
                                          .map(
                                              (r, m) ->
                                                  ProjectLabelResponse.newBuilder()
                                                      .setId(r.get("id", UUID.class).toString())
                                                      .setName(r.get("name", String.class))
                                                      .setColor(r.get("color", String.class))
                                                      .build())
                                          .all()
                                          .collectList()
                                          .map(
                                              all ->
                                                  ProjectLabelFacets.newBuilder()
                                                      .addAllItems(all)
                                                      .build())));
                }))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
