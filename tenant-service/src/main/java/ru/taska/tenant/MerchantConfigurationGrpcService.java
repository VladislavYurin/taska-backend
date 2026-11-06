package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.GetMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.MerchantConfigurationView;
import ru.taska.api.b2b.v1.PublishMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.ReactorMerchantConfigurationServiceGrpc;
import ru.taska.api.b2b.v1.ResolveMerchantConfigurationRequest;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class MerchantConfigurationGrpcService
    extends ReactorMerchantConfigurationServiceGrpc.MerchantConfigurationServiceImplBase {
  private final MerchantConfigurations configurations;

  public MerchantConfigurationGrpcService(MerchantConfigurations configurations) {
    this.configurations = configurations;
  }

  private <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<MerchantConfigurationView> getMerchantConfiguration(
      Mono<GetMerchantConfigurationRequest> request) {
    return inbound(request.flatMap(r -> configurations.get()));
  }

  @Override
  public Mono<MerchantConfigurationView> publishMerchantConfiguration(
      Mono<PublishMerchantConfigurationRequest> request) {
    return inbound(request.flatMap(configurations::publish));
  }

  @Override
  public Mono<MerchantConfigurationSnapshot> resolveMerchantConfiguration(
      Mono<ResolveMerchantConfigurationRequest> request) {
    return inbound(request.flatMap(configurations::resolve));
  }
}
