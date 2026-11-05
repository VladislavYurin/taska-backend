package ru.taska.issuebootstrap;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.issuestorage.TenantIssueStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("tenant-storage")
public class TenantStorageConfiguration {
  @Bean
  public liquibase.integration.spring.SpringLiquibase issueTenantLiquibase(
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
              statement.execute("SET ROLE taska_issue_migrator");
              return connection;
            } catch (java.sql.SQLException error) {
              connection.close();
              throw error;
            }
          }
        };
    var migration = new liquibase.integration.spring.SpringLiquibase();
    migration.setDataSource(owner);
    migration.setChangeLog("classpath:db/issue-tenant/changelog.json");
    migration.setLiquibaseSchema("issue_liquibase");
    return migration;
  }

  @Bean
  @DependsOn("issueTenantLiquibase")
  public org.springframework.beans.factory.InitializingBean safeIssueRuntime(
      ConnectionFactory factory) {
    return () -> {
      // A bounded startup gate on the initialization thread, before any store can be constructed.
      // This is not a WebFlux request/event-loop path; failure must abort startup synchronously.
      Boolean safe;
      try {
        safe =
            DatabaseClient.create(factory)
                .sql(
                    """
                    SELECT current_user='taska_issue_app' AND NOT
                      (rolsuper OR rolinherit OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)
                      AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
                      AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner=r.oid AND relnamespace='taska'::regnamespace)
                      AS safe FROM pg_roles r WHERE rolname=current_user
                    """)
                .map((r, m) -> r.get("safe", Boolean.class))
                .one()
                .block(Duration.ofSeconds(10));
      } catch (RuntimeException failure) {
        throw new IllegalStateException(
            "Issue runtime database role verification failed during startup", failure);
      }
      if (!Boolean.TRUE.equals(safe))
        throw new IllegalStateException("Unsafe issue runtime database role");
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.issuestorage.LifetimeTaskQuota.Authority taskQuotaAuthority() {
    return (c, u, p) ->
        reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.entitlement.EntitlementProjection.Source taskQuotaEntitlements(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorEntitlementServiceGrpc.ReactorEntitlementServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.entitlement.GrpcEntitlementSource(
          stub, trust, java.time.Clock.systemUTC(), "issue-service");
    return (t, v) ->
        reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeIssueRuntime")
  ru.taska.issuestorage.LifetimeTaskQuota taskQuota(
      ConnectionFactory factory,
      ru.taska.issuestorage.LifetimeTaskQuota.Authority authority,
      ru.taska.entitlement.EntitlementProjection.Source source) {
    return new ru.taska.issuestorage.LifetimeTaskQuota(
        new TenantTransactionExecutor(factory), authority, source, java.time.Clock.systemUTC());
  }

  @Bean
  @DependsOn("safeIssueRuntime")
  TenantIssueStore tenantIssues(
      ConnectionFactory factory, ObjectMapper json, ru.taska.issuestorage.LifetimeTaskQuota quota) {
    return new TenantIssueStore(
        new TenantTransactionExecutor(factory), json, java.time.Clock.systemUTC(), quota);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.issuestorage.StorageQuotaOwner.Policy storageQuotaPolicy() {
    return (c, use, r, mime) ->
        reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "STORAGE_POLICY_UNAVAILABLE"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.quota.StorageQuotaProtocol.Authority storageQuotaAuthority(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorQuotaAuthorityServiceGrpc.ReactorQuotaAuthorityServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.quota.GrpcStorageQuotaAuthority(
          stub, trust, java.time.Clock.systemUTC(), "issue-service");
    return new ru.taska.quota.StorageQuotaProtocol.Authority() {
      private <T> reactor.core.publisher.Mono<T> unavailable() {
        return reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "STORAGE_AUTHORITY_UNAVAILABLE"));
      }

      public reactor.core.publisher.Mono<ru.taska.quota.StorageQuotaProtocol.Reservation> reserve(
          ru.taska.quota.StorageQuotaProtocol.Request r) {
        return unavailable();
      }

      public reactor.core.publisher.Mono<ru.taska.quota.StorageQuotaProtocol.Reservation> get(
          java.util.UUID op) {
        return unavailable();
      }

      public reactor.core.publisher.Mono<ru.taska.quota.StorageQuotaProtocol.Reservation> commit(
          ru.taska.quota.StorageQuotaProtocol.Reservation r) {
        return unavailable();
      }

      public reactor.core.publisher.Mono<ru.taska.quota.StorageQuotaProtocol.Reservation> release(
          ru.taska.quota.StorageQuotaProtocol.Reservation r) {
        return unavailable();
      }
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.storage.versioned.VersionedObjects versionedAttachmentObjects(
      software.amazon.awssdk.services.s3.S3AsyncClient client,
      ru.taska.storage.config.StorageProperties properties) {
    return new ru.taska.storage.versioned.S3VersionedObjects(
        client, properties.getBucket(), java.time.Duration.ofSeconds(5));
  }

  @Bean
  @DependsOn("safeIssueRuntime")
  ru.taska.issuestorage.StorageQuotaOwner storageQuotaOwner(
      ConnectionFactory factory,
      ru.taska.quota.StorageQuotaProtocol.Authority authority,
      ru.taska.storage.versioned.VersionedObjects objects,
      ru.taska.issuestorage.StorageQuotaOwner.Policy policy) {
    return new ru.taska.issuestorage.StorageQuotaOwner(
        new TenantTransactionExecutor(factory),
        authority,
        objects,
        policy,
        java.time.Clock.systemUTC());
  }

  /** TAS-343 supplies verified transport and ACL. Old RPC headers cannot mint tenant authority. */
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor issueTenantTransportClosed(
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    var trust = security.getIfAvailable();
    var policies =
        new java.util.HashMap<String, ru.taska.security.tenant.RpcContextSecurity.Policy>();
    for (var method :
        java.util.List.of(
            ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getActivateMethod()
                .getFullMethodName(),
            ru.taska.api.b2b.v1.StorageQuotaOwnerServiceGrpc.getManifestsMethod()
                .getFullMethodName(),
            ru.taska.api.b2b.v1.ProjectQuotaReadServiceGrpc.getGetProjectTaskQuotaMethod()
                .getFullMethodName(),
            ru.taska.api.b2b.v1.ProjectQuotaReadServiceGrpc.getGetTenantTaskQuotaSummaryMethod()
                .getFullMethodName()))
      policies.put(method, new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    ServerInterceptor quotaSecurity =
        trust == null
            ? null
            : new ru.taska.security.tenant.TenantServerInterceptor(trust, policies, environment);
    return new ServerInterceptor() {
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        if (quotaSecurity != null
            && policies.containsKey(call.getMethodDescriptor().getFullMethodName()))
          return quotaSecurity.interceptCall(call, headers, next);
        call.close(
            Status.UNAVAILABLE.withDescription("Issue tenant API is not activated"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
    };
  }
}
