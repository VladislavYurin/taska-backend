package ru.taska.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.security.tenant.*;
import ru.taska.security.tenant.autoconfigure.ManagedRpcTrustAutoConfiguration;

class ManagedRpcStartupTest {
  @TempDir Path directory;

  ApplicationContextRunner runner(ManagedTrustFixture fixture, String name) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ManagedRpcTrustAutoConfiguration.class))
        .withPropertyValues(
            "taska.rpc.context.enabled=true",
            "spring.application.name=" + name,
            "DEPLOY_ENVIRONMENT=test",
            "taska.rpc.trust.snapshot=" + fixture.path("trust.json"),
            "taska.rpc.trust.signing-key=" + fixture.path(name + ".signing.json"),
            "taska.rpc.tls.ca-certificate=" + fixture.path("ca.crt"),
            "taska.rpc.tls.certificate=" + fixture.path(name + ".crt"),
            "taska.rpc.tls.private-key=" + fixture.path(name + ".key"),
            "taska.rpc.trust.auth-host=localhost",
            "taska.rpc.trust.auth-port=1");
  }

  @Test
  void gatewayUsesManagedProvidersAndOneAuthChannelWithoutCycles() throws Exception {
    var fixture = new ManagedTrustFixture(directory, List.of("api-gateway", "auth-service"));
    var endpoint = new GrpcClientProperties.Service("localhost", 1, Duration.ofSeconds(1));
    runner(fixture, "api-gateway")
        .withUserConfiguration(GrpcClientConfig.class)
        .withBean(
            GrpcClientProperties.class,
            () ->
                new GrpcClientProperties(
                    endpoint, endpoint, endpoint, endpoint, endpoint, endpoint))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(RpcContextSecurity.class)
                  .hasSingleBean(ManagedRpcTrust.class)
                  .hasSingleBean(RpcControlClient.class)
                  .hasSingleBean(ManagedRpcPeerInterceptor.class);
              assertThat(
                      context
                          .getBean(
                              ru.taska.api.b2b.v1.ReactorGatewayCredentialServiceGrpc
                                  .ReactorGatewayCredentialServiceStub.class)
                          .getChannel())
                  .isSameAs(context.getBean("authManagedChannel"));
            });
  }

  @Test
  void missingManagedMaterialAndInsecureListenersFailStartup() throws Exception {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ManagedRpcTrustAutoConfiguration.class))
        .withPropertyValues("taska.rpc.context.enabled=true", "spring.application.name=api-gateway")
        .run(context -> assertThat(context).hasFailed());
    var fixture = new ManagedTrustFixture(directory, List.of("project-service", "auth-service"));
    for (String settings :
        List.of(
            "spring.grpc.server.ssl.enabled=false",
            "spring.grpc.server.ssl.secure=false",
            "spring.grpc.server.ssl.client-auth=NONE"))
      runner(fixture, "project-service")
          .withPropertyValues(
              "spring.grpc.server.ssl.enabled=true",
              "spring.grpc.server.ssl.secure=true",
              "spring.grpc.server.ssl.client-auth=REQUIRE",
              "spring.grpc.server.ssl.bundle=rpc",
              settings)
          .run(context -> assertThat(context).hasFailed());
  }
}
