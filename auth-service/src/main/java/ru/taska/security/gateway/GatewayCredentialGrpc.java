package ru.taska.security.gateway;

import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;

@GrpcService
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
public final class GatewayCredentialGrpc
    extends ReactorGatewayCredentialServiceGrpc.GatewayCredentialServiceImplBase {
  private final GatewayCredentialResolver resolver;

  public GatewayCredentialGrpc(GatewayCredentialResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public Mono<GatewayCredential> resolveGatewayCredential(
      Mono<ResolveGatewayCredentialRequest> request) {
    return request
        .flatMap(r -> resolver.resolve(r.getCredential()))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
