package ru.taska.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import ru.taska.security.tenant.LegacyRpcDatabaseBoundary;

/** Legacy repositories are isolated until their native tenant-storage adapters take over. */
@Configuration(proxyBeanMethods = false)
@Profile("!tenant-storage")
public class TrustedRpcStorageConfiguration {
  @Bean
  org.springframework.transaction.ReactiveTransactionManager connectionFactoryTransactionManager(
      io.r2dbc.spi.ConnectionFactory factory,
      @Value("${taska.rpc.context.enabled:false}") boolean enabled) {
    return enabled
        ? new ru.taska.security.tenant.TenantRpcTransactionManager(factory)
        : new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory);
  }

  @Bean
  @DependsOn("liquibase")
  LegacyRpcDatabaseBoundary legacyRpcDatabaseBoundary(
      io.r2dbc.spi.ConnectionFactory factory,
      @Value("${taska.rpc.context.enabled:false}") boolean enabled) {
    var boundary = new LegacyRpcDatabaseBoundary(factory);
    boundary.validateConfiguration(enabled, "issues");
    return boundary;
  }
}
