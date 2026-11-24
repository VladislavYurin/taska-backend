package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.IssueRecipientsRequest;
import ru.taska.api.b2b.v1.IssueRecipientsResponse;
import ru.taska.api.b2b.v1.ReactorIssueRecipientServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

/** Purpose-bound чтение текущих recipients; payload очереди не является разрешением. */
@GrpcService
public final class IssueRecipientRpc
    extends ReactorIssueRecipientServiceGrpc.IssueRecipientServiceImplBase {
  private final TenantIssueMetadataApi api;

  public IssueRecipientRpc(TenantIssueMetadataApi api) {
    this.api = api;
  }

  @Override
  public Mono<IssueRecipientsResponse> resolve(Mono<IssueRecipientsRequest> q) {
    return RpcContextBridge.inbound(q.flatMap(api::recipients))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
