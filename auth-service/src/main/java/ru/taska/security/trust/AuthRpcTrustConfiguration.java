package ru.taska.security.trust;

import io.r2dbc.spi.ConnectionFactory;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import ru.taska.security.session.WebSessionStore;
import ru.taska.security.tenant.FileMtlsRpcChannels;
import ru.taska.security.tenant.ManagedRpcTrust;
import ru.taska.security.tenant.RpcContextSecurity;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class AuthRpcTrustConfiguration {
  @Bean(destroyMethod = "close")
  GrpcMembershipAuthority grpcMembershipAuthority(
      ObjectProvider<RpcContextSecurity> security,
      ManagedRpcTrust trust,
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String cert,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      @Value("${taska.rpc.trust.tenant-host:tenant-service}") String host,
      @Value("${taska.rpc.trust.tenant-port:9099}") int port) {
    return new GrpcMembershipAuthority(
        new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment)
            .create(host, port, "tenant-service"),
        security::getIfAvailable,
        trust);
  }

  @Bean
  AuthRpcTrustBackend authRpcTrustBackend(
      DatabaseClient db,
      ConnectionFactory connections,
      WebSessionStore.MembershipAuthority memberships,
      ManagedRpcTrust trust) {
    return new AuthRpcTrustBackend(db, connections, memberships, trust, Clock.systemUTC());
  }

  @Bean
  RpcReplayCleanup rpcReplayCleanup(AuthRpcTrustBackend backend) {
    return new RpcReplayCleanup(backend);
  }

  static final class RpcReplayCleanup {
    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(RpcReplayCleanup.class);
    private final AuthRpcTrustBackend backend;
    private final java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean();

    RpcReplayCleanup(AuthRpcTrustBackend backend) {
      this.backend = backend;
    }

    @Scheduled(fixedDelayString = "${taska.rpc.trust.replay-cleanup-delay:60000}")
    public void cleanup() {
      if (!running.compareAndSet(false, true)) return;
      reactor.core.publisher.Mono.defer(backend::cleanup)
          .timeout(java.time.Duration.ofSeconds(30))
          .doFinally(ignored -> running.set(false))
          .subscribe(ignored -> {}, e -> LOG.warn("RPC replay cleanup unavailable"));
    }
  }
}
