package ru.taska.security.tenant.autoconfigure;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.GlobalServerInterceptor;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.security.tenant.*;

@AutoConfiguration
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
@ConditionalOnMissingBean(RpcContextSecurity.class)
public class ManagedRpcTrustAutoConfiguration {
  @Bean
  ManagedRpcTrust managedRpcTrust(
      @Value("${taska.rpc.trust.snapshot:}") String snapshot,
      @Value("${taska.rpc.trust.signing-key:}") String key,
      @Value("${spring.application.name}") String service,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      Environment properties) {
    if (snapshot.isBlank() || key.isBlank())
      throw new IllegalStateException("Managed RPC trust paths required");
    if (!java.util.Set.of("api-gateway", "mcp-server", "oauth-server").contains(service)) {
      if (!properties.getProperty("spring.grpc.server.ssl.enabled", Boolean.class, false)
          || !"REQUIRE"
              .equalsIgnoreCase(properties.getProperty("spring.grpc.server.ssl.client-auth", ""))
          || !properties.getProperty("spring.grpc.server.ssl.secure", Boolean.class, true)
          || properties.getProperty("spring.grpc.server.ssl.bundle", "").isBlank())
        throw new IllegalStateException(
            "Trusted RPC requires a TLS bundle and mandatory client authentication");
    }
    return new ManagedRpcTrust(
        Path.of(snapshot), Path.of(key), service, environment, Clock.systemUTC());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnExpression("'${spring.application.name}' != 'auth-service'")
  RpcControlClient rpcControlClient(
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String certificate,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      @Value("${taska.rpc.trust.auth-host:auth-service}") String host,
      @Value("${taska.rpc.trust.auth-port:9091}") int port) {
    return new RpcControlClient(
        new FileMtlsRpcChannels(Path.of(ca), Path.of(certificate), Path.of(key), environment)
            .create(host, port, "auth-service"));
  }

  @Bean
  RpcContextSecurity rpcContextSecurity(
      ManagedRpcTrust trust,
      ObjectProvider<RpcTrustBackend> backend,
      @Value("${spring.application.name}") String service) {
    RpcContextSecurity.Authority authority =
        (context, rpc, policy) ->
            Mono.defer(
                () -> {
                  if (!trust.allowed(context, rpc, policy)) return Mono.just(false);
                  // A SERVICE generation/purpose is managed authority, not a user/session lookup.
                  // This avoids
                  // recursive auth -> tenant membership -> auth checks; mutation replay remains
                  // mandatory.
                  if (context.getContext().getCredentialKind()
                      == CredentialKind.CREDENTIAL_KIND_SERVICE) return Mono.just(true);
                  var current = backend.getIfAvailable();
                  return current == null
                      ? Mono.error(ManagedRpcTrust.unavailable())
                      : current.allowed(context, rpc, policy);
                });
    return new RpcContextSecurity(
        service,
        Clock.systemUTC(),
        trust,
        (issuer, jti, until) ->
            Mono.defer(
                () -> {
                  var current = backend.getIfAvailable();
                  return current == null
                      ? Mono.error(ManagedRpcTrust.unavailable())
                      : current.consume(service, issuer, jti, until);
                }),
        authority);
  }

  @Bean
  @GlobalServerInterceptor
  @Order(Ordered.HIGHEST_PRECEDENCE)
  ManagedRpcPeerInterceptor managedRpcPeer(
      ManagedRpcTrust trust, @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    return new ManagedRpcPeerInterceptor(trust, environment);
  }

  @Bean
  ManagedServiceContexts managedServiceContexts(
      ManagedRpcTrust trust,
      RpcContextSecurity security,
      @Value("${spring.application.name}") String service) {
    return new ManagedServiceContexts(service, trust, security);
  }
}
