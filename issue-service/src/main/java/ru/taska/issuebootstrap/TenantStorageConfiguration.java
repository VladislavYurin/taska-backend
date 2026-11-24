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
@org.springframework.context.annotation.Import(
    ru.taska.issuestorage.StorageQuotaOwnerGrpcService.class)
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
  ru.taska.issuestorage.LifetimeTaskQuota.Authority taskQuotaAuthority(
      org.springframework.beans.factory.ObjectProvider<ru.taska.issuestorage.TenantIssueAccess>
          policies) {
    return (c, use, project) -> {
      var policy = policies.getIfAvailable();
      if (policy == null
          || project == null
          || use == ru.taska.issuestorage.LifetimeTaskQuota.Use.RETIRE)
        return reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
      var action =
          use == ru.taska.issuestorage.LifetimeTaskQuota.Use.CREATE
              ? ru.taska.capability.Action.ISSUE_CREATE
              : ru.taska.capability.Action.ISSUE_SEARCH;
      return policy
          .authorize(action, project, null)
          .map(p -> new ru.taska.issuestorage.LifetimeTaskQuota.Grant(p.bypassBusinessRules()));
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.entitlement.EntitlementProjection.Source taskQuotaEntitlements(
      org.springframework.beans.factory.ObjectProvider<ru.taska.capability.ManagedDomainReads>
          managed,
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorEntitlementServiceGrpc.ReactorEntitlementServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security) {
    var nativeReads = managed.getIfAvailable();
    if (nativeReads != null) return nativeReads::entitlement;
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
  ru.taska.issuestorage.StorageQuotaOwner.Policy storageQuotaPolicy(
      org.springframework.beans.factory.ObjectProvider<ru.taska.issuestorage.AttachmentUploadStore>
          stores,
      org.springframework.beans.factory.ObjectProvider<ru.taska.issuestorage.AttachmentUploadPolicy>
          policies) {
    return (c, use, r, mime) -> {
      if (use == ru.taska.issuestorage.StorageQuotaOwner.Use.PROJECT_CLEANUP)
        return reactor.core.publisher.Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.PERMISSION_DENIED,
                "PROJECT_DELETE_PROOF_REQUIRED"));
      var store = stores.getIfAvailable();
      var policy = policies.getIfAvailable();
      if (store != null && policy != null && r != null)
        return store
            .operation(r.operation())
            .flatMap(
                u -> {
                  if (!u.request().equals(r.request())
                      || !u.tenant().equals(r.tenant())
                      || !u.actor().equals(r.actor()))
                    return reactor.core.publisher.Mono.error(
                        new ru.taska.exception.DomainException(
                            ru.taska.exception.DomainStatus.PERMISSION_DENIED,
                            "UPLOAD_INTENT_MISMATCH"));
                  return policy.check(r.project(), r.issue(), u, use.name()).then();
                });
      return reactor.core.publisher.Mono.error(
          new ru.taska.exception.DomainException(
              ru.taska.exception.DomainStatus.UNAVAILABLE, "STORAGE_POLICY_UNAVAILABLE"));
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.quota.StorageQuotaProtocol.Authority storageQuotaAuthority(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorQuotaAuthorityServiceGrpc.ReactorQuotaAuthorityServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security,
      org.springframework.beans.factory.ObjectProvider<ru.taska.capability.ManagedDomainReads>
          sources) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub == null && sources.getIfAvailable() != null)
      stub =
          ru.taska.api.b2b.v1.ReactorQuotaAuthorityServiceGrpc.newReactorStub(
              sources.getIfAvailable().channel("tenant-service"));
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

  /**
   * TAS-343 использует проверенный транспорт и ACL. Старые заголовки RPC не дают полномочий
   * арендатора.
   */
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
    // Разрешены реализованные CRUD, метки и подписки; поиск и остальные домены закрыты.
    ru.taska.security.tenant.RpcRoutePolicies.policies("issue-service")
        .forEach(
            (rpc, policy) -> {
              String method = rpc.substring(rpc.lastIndexOf('/') + 1);
              if (java.util.Set.of(
                      "CountProjectIssuesBatchInternal",
                      "GetProjectTaskQuotas",
                      "CreateIssue",
                      "GetIssue",
                      "GetIssueDetails",
                      "GetIssueByKey",
                      "PatchIssue",
                      "UpdateIssue",
                      "AssignIssue",
                      "TransitionIssue",
                      "DeleteIssue",
                      "CreateProjectLabel",
                      "UpdateProjectLabel",
                      "DeleteProjectLabel",
                      "ListProjectLabels",
                      "AddIssueLabel",
                      "RemoveIssueLabel",
                      "ListIssueLabels",
                      "WatchIssue",
                      "UnwatchIssue",
                      "ListIssueWatchers",
                      "GetIssueWatchState",
                      "ListAttachments",
                      "GetAttachmentDownloadUrl",
                      "DeleteAttachment",
                      "ReadAttachmentContent",
                      "ReconcileDeletions")
                  .contains(method)) policies.put(rpc, policy);
            });
    policies.put(
        ru.taska.api.issue.attachment.v1.IssueAttachmentServiceGrpc
            .getCreateAttachmentUploadUrlMethod()
            .getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, true));
    policies.put(
        ru.taska.api.issue.attachment.v1.IssueAttachmentServiceGrpc
            .getConfirmAttachmentUploadMethod()
            .getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, true));
    policies.put(
        ru.taska.api.issue.attachment.v1.AttachmentAdmissionServiceGrpc.getReconcileUploadsMethod()
            .getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, true));
    policies.put(
        ru.taska.api.issue.attachment.v1.AttachmentAdmissionServiceGrpc.getCheckAdmissionMethod()
            .getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    policies.put(
        ru.taska.api.b2b.v1.ProjectLabelsServiceGrpc.getReadMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
    policies.put(
        ru.taska.api.b2b.v1.IssueRecipientServiceGrpc.getResolveMethod().getFullMethodName(),
        new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
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
