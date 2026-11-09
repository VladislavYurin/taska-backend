package ru.taska.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.grpc.ManagedChannel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import ru.taska.api.b2b.v1.ReactorGatewayCredentialServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.security.tenant.*;

class TrustedRpcConfigurationTest {
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("rpc-config-test")
  static class Providers {
    @Bean
    GrpcClientProperties properties() {
      var service = new GrpcClientProperties.Service("unused.invalid", 1, Duration.ofSeconds(1));
      return new GrpcClientProperties(service, service, service, service, service, service);
    }

    @Bean
    RpcContextSecurity security() {
      return mock(RpcContextSecurity.class);
    }
  }

  @Test
  void enabledGatewayResolvesCredentialStubFromTheSingleAuthChannelWithoutCycles() {
    new ApplicationContextRunner()
        .withBean(
            TrustedRpcChannels.class, () -> (host, port, audience) -> mock(ManagedChannel.class))
        .withUserConfiguration(GrpcClientConfig.class, Providers.class)
        .withPropertyValues(
            "taska.rpc.context.enabled=true", "spring.profiles.active=rpc-config-test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var channel = context.getBean("authManagedChannel", ManagedChannel.class);
              assertThat(
                      context
                          .getBean(
                              ReactorGatewayCredentialServiceGrpc
                                  .ReactorGatewayCredentialServiceStub.class)
                          .getChannel())
                  .isSameAs(channel);
              assertThat(
                      context.getBeansOfType(
                          ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub
                              .class))
                  .hasSize(1);
            });
  }
}
