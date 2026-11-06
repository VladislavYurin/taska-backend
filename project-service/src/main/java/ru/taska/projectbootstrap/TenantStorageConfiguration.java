package ru.taska.projectbootstrap;

import io.grpc.*;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.projectstorage.TenantProjectStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("tenant-storage")
public class TenantStorageConfiguration {
  @Bean
  public liquibase.integration.spring.SpringLiquibase projectTenantLiquibase(
      @Value("${spring.liquibase.url}") String url,
      @Value("${spring.liquibase.user}") String user,
      @Value("${spring.liquibase.password}") String password) {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
    var owner =
        new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            try (var statement = connection.createStatement()) {
              statement.execute("SET ROLE taska_project_migrator");
              return connection;
            } catch (java.sql.SQLException error) {
              connection.close();
              throw error;
            }
          }
        };
    var migration = new liquibase.integration.spring.SpringLiquibase();
    migration.setDataSource(owner);
    migration.setChangeLog("classpath:db/project-tenant/changelog.json");
    migration.setLiquibaseSchema("project_liquibase");
    return migration;
  }

  @Bean
  @DependsOn("projectTenantLiquibase")
  public org.springframework.beans.factory.InitializingBean safeProjectRuntime(
      ConnectionFactory factory) {
    return () -> {
      // A bounded startup gate on the initialization thread, before any store can be constructed.
      // This is not a WebFlux request/event-loop path; failure must abort startup synchronously.
      Boolean safe =
          DatabaseClient.create(factory)
              .sql(
                  """
                  SELECT current_user='taska_project_app' AND NOT
                    (rolsuper OR rolinherit OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)
                    AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
                    AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner=r.oid AND relnamespace='taska'::regnamespace)
                    AS safe FROM pg_roles r WHERE rolname=current_user
                  """)
              .map((r, m) -> r.get("safe", Boolean.class))
              .one()
              .block(Duration.ofSeconds(10));
      if (!Boolean.TRUE.equals(safe))
        throw new IllegalStateException("Unsafe project runtime database role");
    };
  }

  @Bean
  @DependsOn("safeProjectRuntime")
  TenantProjectStore tenantProjects(ConnectionFactory factory, ObjectMapper json) {
    return new TenantProjectStore(new TenantTransactionExecutor(factory), json);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.projectstorage.ProjectQuotaOwner.Policy projectQuotaPolicy() {
    return (c, use, op, project) ->
        reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "PROJECT_POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.quota.ProjectQuotaProtocol.Authority projectQuotaAuthority(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorQuotaAuthorityServiceGrpc.ReactorQuotaAuthorityServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.quota.GrpcProjectQuotaAuthority(
          stub, trust, java.time.Clock.systemUTC(), "project-service");
    return new ru.taska.quota.ProjectQuotaProtocol.Authority() {
      public reactor.core.publisher.Mono<ru.taska.quota.ProjectQuotaProtocol.Reservation> get(
          java.util.UUID op) {
        return reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "PROJECT_QUOTA_UNAVAILABLE"));
      }

      public reactor.core.publisher.Mono<ru.taska.quota.ProjectQuotaProtocol.Reservation> reserve(
          java.util.UUID op, java.util.UUID resource, java.time.Instant expiry) {
        return get(op);
      }

      public reactor.core.publisher.Mono<ru.taska.quota.ProjectQuotaProtocol.Reservation> commit(
          ru.taska.quota.ProjectQuotaProtocol.Reservation r) {
        return get(r.operation());
      }

      public reactor.core.publisher.Mono<ru.taska.quota.ProjectQuotaProtocol.Reservation> release(
          ru.taska.quota.ProjectQuotaProtocol.Reservation r) {
        return get(r.operation());
      }
    };
  }

  @Bean
  @DependsOn("safeProjectRuntime")
  ru.taska.projectstorage.ProjectQuotaOwner projectQuotaOwner(
      ConnectionFactory factory,
      ru.taska.quota.ProjectQuotaProtocol.Authority authority,
      ru.taska.projectstorage.ProjectQuotaOwner.Policy policy) {
    return new ru.taska.projectstorage.ProjectQuotaOwner(
        new TenantTransactionExecutor(factory), authority, policy, java.time.Clock.systemUTC());
  }

  /** TAS-340 supplies verified transport and ACL. Old RPC headers cannot mint tenant authority. */
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor projectTenantTransportClosed(
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          provider,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    var security = provider.getIfAvailable();
    var policies =
        new java.util.HashMap<String, ru.taska.security.tenant.RpcContextSecurity.Policy>();
    policies.put(
        ru.taska.api.b2b.v1.ProjectQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    policies.put(
        ru.taska.api.b2b.v1.ProjectQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    policies.put(
        ru.taska.api.b2b.v1.ProjectQuotaOwnerServiceGrpc.getOpenMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    policies.put(
        ru.taska.api.b2b.v1.ProjectQuotaOwnerServiceGrpc.getInventoryMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    ServerInterceptor quota =
        security == null
            ? null
            : new ru.taska.security.tenant.TenantServerInterceptor(security, policies, environment);
    return new ServerInterceptor() {
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        if (quota != null && policies.containsKey(call.getMethodDescriptor().getFullMethodName()))
          return quota.interceptCall(call, headers, next);
        call.close(
            Status.UNAVAILABLE.withDescription("Project tenant API is not activated"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
    };
  }
}
