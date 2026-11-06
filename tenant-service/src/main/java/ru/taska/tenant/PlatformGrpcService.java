package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ListPlatformFlagsRpcRequest;
import ru.taska.api.b2b.v1.PlatformFlag;
import ru.taska.api.b2b.v1.PlatformFlagPage;
import ru.taska.api.b2b.v1.ReactorPlatformServiceGrpc;
import ru.taska.api.b2b.v1.UpdatePlatformFlagRpcRequest;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class PlatformGrpcService extends ReactorPlatformServiceGrpc.PlatformServiceImplBase {
  private final PlatformFlags flags;

  public PlatformGrpcService(PlatformFlags flags) {
    this.flags = flags;
  }

  @Override
  public Mono<PlatformFlagPage> listPlatformFlags(Mono<ListPlatformFlagsRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(flags::list))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<PlatformFlag> updatePlatformFlag(Mono<UpdatePlatformFlagRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(flags::update))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.PlatformConfigurationSnapshot> getPlatformConfigurationSnapshot(
      Mono<ru.taska.api.b2b.v1.GetPlatformConfigurationSnapshotRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(ignored -> flags.snapshot()))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
