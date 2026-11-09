package ru.taska.config;

import io.grpc.ServerInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import ru.taska.security.tenant.*;

@Profile("!tenant-storage")
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
public class TrustedRpcConfiguration {
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor legacyTrustedRpc(
      ObjectProvider<RpcContextSecurity> security,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      LegacyRpcDatabaseBoundary database) {
    return new LegacyRpcServerInterceptor(
        "project-service", security.getIfAvailable(), environment, database);
  }
}
