package ru.taska.security.tenant.autoconfigure;

import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.security.tenant.*;

/** Raw mTLS channels: managed reads sign their own exact request, without legacy interceptors. */
@AutoConfiguration(after = ManagedRpcTrustAutoConfiguration.class)
@ConditionalOnBean(ManagedServiceContexts.class)
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class NativeDomainReadsAutoConfiguration {
  @Bean(destroyMethod = "close")
  ManagedDomainReads managedDomainReads(
      ManagedServiceContexts contexts, RpcContextSecurity security, Environment env) {
    var tls =
        new FileMtlsRpcChannels(
            Path.of(env.getRequiredProperty("taska.rpc.tls.ca-certificate")),
            Path.of(env.getRequiredProperty("taska.rpc.tls.certificate")),
            Path.of(env.getRequiredProperty("taska.rpc.tls.private-key")),
            env.getProperty("DEPLOY_ENVIRONMENT", "local"));
    var ports =
        Map.of(
            "auth-service",
            9091,
            "tenant-service",
            9099,
            "billing-service",
            9100,
            "project-service",
            9096,
            "workflow-service",
            9093,
            "issue-service",
            9094);
    var channels = new HashMap<String, io.grpc.ManagedChannel>();
    try {
      ports.forEach(
          (service, port) ->
              channels.put(
                  service,
                  tls.create(
                      env.getProperty("taska.domain." + service + ".host", service),
                      env.getProperty("taska.domain." + service + ".port", Integer.class, port),
                      service)));
      return new ManagedDomainReads(contexts, security, channels);
    } catch (RuntimeException e) {
      channels.values().forEach(io.grpc.ManagedChannel::shutdownNow);
      throw e;
    }
  }
}
