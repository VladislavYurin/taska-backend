package ru.taska.tenant;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.grpc.server.GlobalServerInterceptor;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc;
import ru.taska.api.b2b.v1.PlatformServiceGrpc;
import ru.taska.api.b2b.v1.TenantMembershipServiceGrpc;
import ru.taska.api.b2b.v1.TenantServiceGrpc;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;
import ru.taska.tenant.RegistryDependencies.FreeAdmission;
import ru.taska.tenant.RegistryDependencies.PublishedPlans;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
public class TenantConfiguration {
  @Bean
  liquibase.integration.spring.SpringLiquibase registryLiquibase(
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
              statement.execute("SET ROLE taska_tenant_migrator");
              return connection;
            } catch (java.sql.SQLException error) {
              connection.close();
              throw error;
            }
          }
        };
    var liquibase = new liquibase.integration.spring.SpringLiquibase();
    liquibase.setDataSource(owner);
    liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
    liquibase.setLiquibaseSchema("tenant_liquibase");
    return liquibase;
  }

  @Bean
  TenantTransactionExecutor tenantTransactions(ConnectionFactory factory) {
    return new TenantTransactionExecutor(factory);
  }

  @Bean
  @ConditionalOnMissingBean
  PublishedPlans publishedPlans(
      ObjectProvider<ru.taska.api.b2b.v1.ReactorBillingServiceGrpc.ReactorBillingServiceStub> stubs,
      ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new GrpcPublishedPlans(stub, trust, Clock.systemUTC());
    return id ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  FreeEligibilityDependencies.IdentityAuthority freeIdentityAuthority() {
    return actor ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  FreeEligibilityDependencies.PurposeAuthority freePurposeAuthority() {
    return () ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @ConditionalOnMissingBean
  FreeEligibilityDependencies.HmacAuthority freeHmacAuthority() {
    return new FreeEligibilityDependencies.HmacAuthority() {
      public Mono<FreeEligibilityDependencies.KeySet> keys() {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
      }

      public Mono<byte[]> sign(int version, byte[] message) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  FreeEligibilityDependencies.DispositionAuthority freeDispositionAuthority() {
    return (caller, id) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  FreeEligibilityDependencies.CreationPlans freeCreationPlans() {
    return () ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  FreeEligibilityDependencies.AdmissionAuthority freePlanAdmissionAuthority() {
    return (c, owner, version) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  FreeEligibilityLedger freeEligibilityLedger(
      ConnectionFactory factory,
      TenantTransactionExecutor transactions,
      FreeEligibilityDependencies.IdentityAuthority identities,
      FreeEligibilityDependencies.HmacAuthority keys,
      FreeEligibilityDependencies.PurposeAuthority purposes,
      FreeEligibilityDependencies.DispositionAuthority dispositions,
      FreeEligibilityDependencies.CreationPlans plans,
      FreeEligibilityDependencies.AdmissionAuthority admissions) {
    return new FreeEligibilityLedger(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        org.springframework.transaction.reactive.TransactionalOperator.create(
            new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory)),
        transactions,
        identities,
        keys,
        purposes,
        dispositions,
        plans,
        Clock.systemUTC(),
        admissions);
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantRegistryService tenantRegistry(
      TenantTransactionExecutor transactions, PublishedPlans plans, FreeAdmission admission) {
    return new TenantRegistryService(
        transactions,
        plans,
        admission,
        Clock.systemUTC(),
        db -> PlatformFlags.duration(db, "UNPAID_CREATION_WINDOW_SECONDS"));
  }

  @Bean
  @DependsOn("registryLiquibase")
  org.springframework.beans.factory.InitializingBean safeRuntimeRole(ConnectionFactory factory) {
    return () -> {
      Boolean safe =
          org.springframework.r2dbc.core.DatabaseClient.create(factory)
              .sql(
                  "SELECT current_user='taska_tenant_app' AND NOT (rolsuper OR rolinherit OR"
                      + " rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication) AND NOT"
                      + " EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)"
                      + " AND NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.relowner=r.oid AND"
                      + " c.relnamespace='taska'::regnamespace) AS safe FROM pg_roles r WHERE"
                      + " rolname=current_user")
              .map((row, metadata) -> row.get("safe", Boolean.class))
              .one()
              .block(Duration.ofSeconds(10));
      if (!Boolean.TRUE.equals(safe))
        throw new IllegalStateException("Unsafe tenant runtime database role");
    };
  }

  @Bean
  MembershipCursor membershipCursor(@Value("${taska.tenant.membership-cursor-key:}") String key) {
    return new MembershipCursor(key, Clock.systemUTC());
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantMembershipService tenantMemberships(
      TenantTransactionExecutor transactions,
      MembershipCursor cursor,
      @Value(
              "${taska.tenant.membership-readers:auth-service,api-gateway,project-service,workflow-service,issue-service,tenant-service}")
          java.util.Set<String> readers) {
    return new TenantMembershipService(transactions, cursor, readers);
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  PlatformFlags platformFlags(
      ConnectionFactory factory,
      @Value("${taska.tenant.platform-flags-cursor-key:}") String cursorKey) {
    return new PlatformFlags(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        org.springframework.transaction.reactive.TransactionalOperator.create(
            new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory)),
        new PlatformFlagCursor(cursorKey, Clock.systemUTC()));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  MerchantConfigurations merchantConfigurations(ConnectionFactory factory, PlatformFlags access) {
    return new MerchantConfigurations(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        org.springframework.transaction.reactive.TransactionalOperator.create(
            new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory)),
        access);
  }

  @Bean
  @ConditionalOnMissingBean
  TenantProvisioning.Dependencies provisioningDependencies(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorBillingProvisioningServiceGrpc
                  .ReactorBillingProvisioningServiceStub>
          billing,
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorWorkflowProvisioningServiceGrpc
                  .ReactorWorkflowProvisioningServiceStub>
          workflow,
      ObjectProvider<RpcContextSecurity> trust) {
    var b = billing.getIfAvailable();
    var w = workflow.getIfAvailable();
    var security = trust.getIfAvailable();
    if (b != null && w != null && security != null)
      return new GrpcProvisioningDependencies(b, w, security, Clock.systemUTC());
    return new TenantProvisioning.Dependencies() {
      public Mono<Void> workflow(
          java.util.UUID tenant, java.util.UUID operation, java.util.UUID template) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
      }

      public Mono<Void> billing(
          java.util.UUID tenant, java.util.UUID operation, java.util.UUID plan, boolean free) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
      }
    };
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantProvisioning tenantProvisioning(
      TenantTransactionExecutor transactions, TenantProvisioning.Dependencies dependencies) {
    return new TenantProvisioning(transactions, dependencies, Clock.systemUTC());
  }

  @Bean
  TenantProvisioningWorker provisioningWorker(
      ConnectionFactory factory,
      ObjectProvider<RpcContextSecurity> security,
      TenantProvisioning provisioning) {
    return new TenantProvisioningWorker(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        () -> {
          var trust = security.getIfAvailable();
          return trust == null
              ? null
              : new ru.taska.security.tenant.ProvisioningAuthority(trust, Clock.systemUTC());
        },
        provisioning);
  }

  @Bean
  @ConditionalOnMissingBean
  TenantCreationDecisions.Authority initialPaymentDecisions(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorPaymentSettlementServiceGrpc
                  .ReactorPaymentSettlementServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new GrpcInitialPaymentDecisions(stub, trust, Clock.systemUTC());
    return (tenant, decision) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantCreationLifecycle creationLifecycle(
      TenantTransactionExecutor transactions, TenantCreationDecisions.Authority decisions) {
    return new TenantCreationLifecycle(transactions, decisions, Clock.systemUTC());
  }

  @Bean
  TenantCreationExpiryWorker creationExpiryWorker(
      ConnectionFactory factory,
      ObjectProvider<RpcContextSecurity> security,
      TenantCreationLifecycle lifecycle) {
    return new TenantCreationExpiryWorker(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        () -> {
          var trust = security.getIfAvailable();
          return trust == null
              ? null
              : new ru.taska.security.tenant.TenantLifecycleAuthority(trust, Clock.systemUTC());
        },
        lifecycle,
        Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  SeatQuotaStore.Authority seatQuotaAuthority() {
    return (context, command) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.entitlement.EntitlementProjection.Source quotaEntitlements(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorEntitlementServiceGrpc.ReactorEntitlementServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security,
      ObjectProvider<ru.taska.capability.ManagedDomainReads> sources) {
    var reads = sources.getIfAvailable();
    if (reads != null) return reads::entitlement;
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.entitlement.GrpcEntitlementSource(
          stub, trust, Clock.systemUTC(), "tenant-service");
    return (tenant, minimum) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.entitlement.TenantTaskQuotaSource taskQuotaSource(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorProjectQuotaReadServiceGrpc
                  .ReactorProjectQuotaReadServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.entitlement.GrpcTenantTaskQuotaSource(
          stub, trust, Clock.systemUTC(), "tenant-service");
    return t ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "TASK_QUOTA_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  ProjectQuotaStore.Policy projectQuotaPolicy(
      TenantTransactionExecutor tx,
      ObjectProvider<ru.taska.capability.ManagedDomainReads> sources,
      ObjectProvider<ru.taska.security.tenant.RpcTrustBackend> identities,
      ObjectProvider<RpcContextSecurity> trust) {
    var reads = sources.getIfAvailable();
    var backend = identities.getIfAvailable();
    var security = trust.getIfAvailable();
    if (reads != null && backend != null && security != null)
      return new NativeProjectAdmission(tx, reads, backend, security);
    return (c, command, operation) ->
        Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "PROJECT_POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.quota.ProjectQuotaProtocol.Owner projectQuotaOwner(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorProjectQuotaOwnerServiceGrpc
                  .ReactorProjectQuotaOwnerServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security,
      ObjectProvider<ru.taska.capability.ManagedDomainReads> sources) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    var reads = sources.getIfAvailable();
    if (stub == null && reads != null)
      stub =
          ru.taska.api.b2b.v1.ReactorProjectQuotaOwnerServiceGrpc.newReactorStub(
              reads.channel("project-service"));
    if (stub != null && trust != null)
      return new ru.taska.quota.GrpcProjectQuotaOwner(
          stub, trust, Clock.systemUTC(), "tenant-service");
    return new ru.taska.quota.ProjectQuotaProtocol.Owner() {
      public Mono<ru.taska.quota.ProjectQuotaProtocol.Receipt> inspect(
          ru.taska.quota.ProjectQuotaProtocol.Reservation r) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "PROJECT_OWNER_UNAVAILABLE"));
      }

      public Mono<ru.taska.quota.ProjectQuotaProtocol.Receipt> cancel(
          ru.taska.quota.ProjectQuotaProtocol.Reservation r) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "PROJECT_OWNER_UNAVAILABLE"));
      }

      public Mono<ru.taska.quota.ProjectQuotaProtocol.Receipt> open(
          ru.taska.quota.ProjectQuotaProtocol.Reservation r) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "PROJECT_OWNER_UNAVAILABLE"));
      }

      public Mono<ru.taska.quota.ProjectQuotaProtocol.Inventory> inventory(java.util.UUID tenant) {
        return Mono.error(
            TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "PROJECT_OWNER_UNAVAILABLE"));
      }
    };
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  ProjectQuotaStore projectQuotas(
      TenantTransactionExecutor tx,
      ProjectQuotaStore.Policy policy,
      ru.taska.quota.ProjectQuotaProtocol.Owner owner,
      ru.taska.entitlement.EntitlementProjection.Source billing) {
    return new ProjectQuotaStore(tx, policy, owner, billing, Clock.systemUTC());
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  StorageQuotaStore.Policy storageQuotaPolicy() {
    return (c, command, target) ->
        Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "STORAGE_POLICY_UNAVAILABLE"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  ru.taska.quota.StorageQuotaProtocol.Owner storageQuotaOwner(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorStorageQuotaOwnerServiceGrpc
                  .ReactorStorageQuotaOwnerServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity>
          security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.quota.GrpcStorageQuotaOwner(
          stub, trust, Clock.systemUTC(), "tenant-service");
    return new ru.taska.quota.StorageQuotaProtocol.Owner() {
      private <T> Mono<T> unavailable() {
        return Mono.error(
            new ru.taska.exception.DomainException(
                ru.taska.exception.DomainStatus.UNAVAILABLE, "STORAGE_OWNER_UNAVAILABLE"));
      }

      public Mono<ru.taska.quota.StorageQuotaProtocol.Receipt> inspect(
          ru.taska.quota.StorageQuotaProtocol.Reservation r) {
        return unavailable();
      }

      public Mono<ru.taska.quota.StorageQuotaProtocol.Receipt> cancel(
          ru.taska.quota.StorageQuotaProtocol.Reservation r) {
        return unavailable();
      }

      public Mono<ru.taska.quota.StorageQuotaProtocol.Receipt> activate(
          ru.taska.quota.StorageQuotaProtocol.Reservation r) {
        return unavailable();
      }

      public Mono<ru.taska.quota.StorageQuotaProtocol.ManifestPage> manifests(
          java.util.UUID t, java.util.UUID after, long version) {
        return unavailable();
      }
    };
  }

  @Bean
  StorageQuotaStore storageQuotas(
      TenantTransactionExecutor tx,
      StorageQuotaStore.Policy policy,
      ru.taska.quota.StorageQuotaProtocol.Owner owner,
      ru.taska.entitlement.EntitlementProjection.Source billing) {
    return new StorageQuotaStore(tx, policy, owner, billing, Clock.systemUTC());
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  SeatQuotaStore seatQuotas(
      TenantTransactionExecutor transactions,
      SeatQuotaStore.Authority authority,
      ru.taska.entitlement.EntitlementProjection.Source source,
      ru.taska.entitlement.TenantTaskQuotaSource tasks,
      ProjectQuotaStore projects,
      StorageQuotaStore storage) {
    return new SeatQuotaStore(
        transactions, authority, source, Clock.systemUTC(), tasks, projects, storage);
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantMembershipMutationService tenantMembershipMutations(
      TenantTransactionExecutor transactions) {
    return new TenantMembershipMutationService(transactions, Clock.systemUTC());
  }

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor registrySecurity(
      ObjectProvider<RpcContextSecurity> provider,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    RpcContextSecurity security = provider.getIfAvailable();
    if (security != null)
      return new TenantServerInterceptor(
          security,
          Map.ofEntries(
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.TenantCapabilitiesServiceGrpc.getGetTenantCapabilitiesMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getSetTenantMemberRoleMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getLeaveTenantMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.QuotaAuthorityServiceGrpc.getReserveQuotaMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.QuotaAuthorityServiceGrpc.getCommitQuotaMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.QuotaAuthorityServiceGrpc.getReleaseQuotaMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.QuotaAuthorityServiceGrpc.getGetQuotaReservationMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.TenantPaymentStateServiceGrpc.getGetTenantPaymentStateMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getGetTenantQuotasMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getCancelTenantCreationMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc
                      .getExpireTenantCreationMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc
                      .getApplyTenantInitialPaymentDecisionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.TenantProvisioningServiceGrpc
                      .getResumeTenantProvisioningMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              java.util.Map.entry(
                  TenantServiceGrpc.getGetTenantOperationMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  MerchantConfigurationServiceGrpc.getGetMerchantConfigurationMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  MerchantConfigurationServiceGrpc.getPublishMerchantConfigurationMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              java.util.Map.entry(
                  MerchantConfigurationServiceGrpc.getResolveMerchantConfigurationMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  PlatformServiceGrpc.getListPlatformFlagsMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  PlatformServiceGrpc.getUpdatePlatformFlagMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              java.util.Map.entry(
                  PlatformServiceGrpc.getGetPlatformConfigurationSnapshotMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              java.util.Map.entry(
                  TenantServiceGrpc.getGetTenantMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantServiceGrpc.getListTenantMembersMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantMembershipServiceGrpc.getGetMembershipMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.getGetTenantCreationOptionsMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc
                      .getCancelFreeTenantAdmissionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.getGetFreeAdmissionReceiptMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.getAdmitFreeTenantMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              java.util.Map.entry(
                  ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc
                      .getApplyFreeEligibilityDispositionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true))),
          environment);
    // No permissive bootstrap. Keys/replay/online-authority adapters must be explicitly supplied.
    return new ServerInterceptor() {
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        call.close(
            Status.UNAVAILABLE.withDescription("Tenant trust provider unavailable"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
    };
  }
}
