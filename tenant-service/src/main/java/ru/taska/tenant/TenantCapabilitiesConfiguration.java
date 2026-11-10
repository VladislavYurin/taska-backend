package ru.taska.tenant;

import java.nio.file.Path;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class TenantCapabilitiesConfiguration {
  @Bean(destroyMethod = "close")
  TenantCapabilitySources tenantCapabilitySources(
      ManagedServiceContexts contexts,
      RpcContextSecurity security,
      PlatformFlags flags,
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String cert,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      @Value("${taska.capabilities.billing-host:billing-service}") String host,
      @Value("${taska.capabilities.billing-port:9100}") int port) {
    return new TenantCapabilitySources(
        new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment)
            .create(host, port, "billing-service"),
        contexts,
        security,
        flags);
  }

  @Bean
  TenantCapabilities tenantCapabilities(
      TenantTransactionExecutor transactions,
      RpcTrustBackend identities,
      TenantCapabilitySources source) {
    var clock = Clock.systemUTC();
    var flags =
        new PlatformConfigurationSnapshotCache(
            source::configuration,
            (context, event) -> reactor.core.publisher.Mono.just(false),
            clock,
            Duration.ofSeconds(30));
    return new TenantCapabilities(
        transactions, identities, flags, source::current, source::plan, clock);
  }
}
