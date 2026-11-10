package ru.taska.service;

import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.domain.GatewayContext;
import ru.taska.security.tenant.*;

/** Client owns a private mTLS channel. Legacy mode cannot synthesize a tenant principal. */
@Service
public class TenantCapabilitiesClient implements AutoCloseable {
  private final ObjectProvider<RpcContextSecurity> security;
  private final String ca, cert, key, environment, host;
  private final int port;
  private ManagedChannel channel;

  public TenantCapabilitiesClient(
      ObjectProvider<RpcContextSecurity> security,
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String cert,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      @Value("${taska.capabilities.tenant-host:tenant-service}") String host,
      @Value("${taska.capabilities.tenant-port:9099}") int port) {
    this.security = security;
    this.ca = ca;
    this.cert = cert;
    this.key = key;
    this.environment = environment;
    this.host = host;
    this.port = port;
  }

  private synchronized ManagedChannel channel() {
    if (channel == null)
      channel =
          new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment)
              .create(host, port, "tenant-service");
    return channel;
  }

  public Mono<Capabilities> get(UUID tenant, GatewayContext context) {
    return Mono.defer(
            () -> {
              var guard = security.getIfAvailable();
              var parent = context.verified();
              if (parent == null || guard == null)
                return Mono.error(Status.UNAVAILABLE.asRuntimeException());
              if (!tenant.toString().equals(parent.tenantId()))
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              String rpc =
                  TenantCapabilitiesServiceGrpc.getGetTenantCapabilitiesMethod()
                      .getFullMethodName();
              var policy = new RpcContextSecurity.Policy(true, false);
              var request =
                  GetTenantCapabilitiesRpcRequest.newBuilder()
                      .setTenantId(tenant.toString())
                      .setContext(
                          guard.delegate(
                              parent, "tenant-service", "getTenantCapabilities", parent.scopes()))
                      .build();
              return guard
                  .sign(parent, request, rpc, policy)
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return ReactorTenantCapabilitiesServiceGrpc.newReactorStub(channel())
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                            .getTenantCapabilities(request)
                            .doOnNext(result -> validate(result, tenant, parent));
                      });
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private static void validate(Capabilities result, UUID tenant, VerifiedTenantContext actor) {
    try {
      var now = java.time.Instant.now();
      var expires = java.time.Instant.parse(result.getExpiresAt());
      var evaluated = java.time.Instant.parse(result.getEvaluatedAt());
      if (!expires.isAfter(evaluated)
          || java.time.Duration.between(evaluated, expires)
                  .compareTo(java.time.Duration.ofSeconds(30))
              > 0
          || evaluated.isAfter(now.plusSeconds(5))) throw new IllegalArgumentException();
      if (!tenant.toString().equals(result.getTenantId())
          || !actor.actorId().equals(result.getActorUserId())
          || !now.isBefore(expires)
          || expires.isAfter(now.plusSeconds(30))
          || result.getItemsCount() == 0
          || result.getItemsCount() > 200) throw new IllegalArgumentException();
      var names = new HashSet<String>();
      var known =
          ru.taska.capability.CapabilityEvaluator.tenantActions().stream()
              .map(ru.taska.capability.Action::wire)
              .collect(java.util.stream.Collectors.toSet());
      for (var item : result.getItemsList())
        if (!known.contains(item.getAction())
            || !names.add(item.getAction())
            || item.getDenialCodesList().contains(ErrorCode.UNRECOGNIZED)
            || item.getRestrictionsList().contains(RestrictionCode.UNRECOGNIZED))
          throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw Status.UNAVAILABLE.withDescription("Invalid capability response").asRuntimeException();
    }
  }

  @jakarta.annotation.PreDestroy
  public synchronized void close() {
    if (channel != null) channel.shutdownNow();
  }
}
