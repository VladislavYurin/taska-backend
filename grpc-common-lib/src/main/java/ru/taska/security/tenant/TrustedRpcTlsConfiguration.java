package ru.taska.security.tenant;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
public class TrustedRpcTlsConfiguration {
  @Bean
  @ConditionalOnMissingBean(TrustedRpcChannels.class)
  public TrustedRpcChannels trustedRpcChannels(
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String cert,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    if (ca.isBlank() || cert.isBlank() || key.isBlank())
      throw new IllegalStateException("RPC CA/certificate/private-key paths are required");
    return new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment);
  }
}
