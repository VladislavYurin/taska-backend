package ru.taska.billing;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.BillingServiceGrpc;
import ru.taska.billing.SubscriptionDependencies.CoverageEvidence;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.billing.SubscriptionDependencies.RevocationEvidence;
import ru.taska.billing.SubscriptionDependencies.SettlementEvidenceAuthority;
import ru.taska.billing.payment.PaymentAttemptStore;
import ru.taska.billing.payment.BillingOperationStore;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;

@Configuration
public class BillingConfiguration {
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
              statement.execute("SET ROLE taska_billing_migrator");
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
    liquibase.setLiquibaseSchema("billing_liquibase");
    return liquibase;
  }

  @Bean
  @DependsOn("registryLiquibase")
  org.springframework.beans.factory.InitializingBean safeRuntimeRole(ConnectionFactory factory) {
    return () -> {
      Boolean safe =
          org.springframework.r2dbc.core.DatabaseClient.create(factory)
              .sql(
                  "SELECT current_user='taska_billing_app' AND NOT (rolsuper OR rolinherit OR"
                      + " rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication) AND NOT"
                      + " EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)"
                      + " AND NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.relowner=r.oid AND"
                      + " c.relnamespace='taska'::regnamespace) AS safe FROM pg_roles r WHERE"
                      + " rolname=current_user")
              .map((row, metadata) -> row.get("safe", Boolean.class))
              .one()
              .block(Duration.ofSeconds(10));
      if (!Boolean.TRUE.equals(safe))
        throw new IllegalStateException("Unsafe billing runtime database role");
    };
  }

  @Bean
  CatalogCursor catalogCursor(@Value("${BILLING_CATALOG_CURSOR_KEY:}") String key) {
    return new CatalogCursor(key, Clock.systemUTC());
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  BillingCatalog catalog(ConnectionFactory factory, CatalogCursor cursor) {
    return new BillingCatalog(
        org.springframework.r2dbc.core.DatabaseClient.create(factory),
        org.springframework.transaction.reactive.TransactionalOperator.create(
            new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory)),
        cursor);
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  PaymentAttemptStore paymentAttemptStore(ConnectionFactory factory) {
    return new PaymentAttemptStore(new TenantTransactionExecutor(factory), Clock.systemUTC());
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  BillingOperationStore billingOperationStore(ConnectionFactory factory) {
    return new BillingOperationStore(new TenantTransactionExecutor(factory), Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  FinancialReadAuthority subscriptionReadAuthority() {
    return context ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  SettlementEvidenceAuthority subscriptionSettlementAuthority() {
    return new SettlementEvidenceAuthority() {
      public Mono<CoverageEvidence> confirmed(DatabaseClient db, UUID tenant, UUID evidence) {
        return Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
      }

      public Mono<RevocationEvidence> reversed(DatabaseClient db, UUID tenant, UUID evidence) {
        return Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
      }
    };
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  SubscriptionStore subscriptions(
      ConnectionFactory factory,
      FinancialReadAuthority readers,
      SettlementEvidenceAuthority evidence,
      @Value("${BILLING_CATALOG_CURSOR_KEY:}") String cursorKey,
      EntitlementStore entitlements) {
    var clock = Clock.systemUTC();
    return new SubscriptionStore(
        new TenantTransactionExecutor(factory),
        readers,
        evidence,
        clock,
        new PeriodCursor(cursorKey, clock),
        entitlements);
  }

  @Bean
  @ConditionalOnMissingBean
  PlanChangeDependencies.Policy subscriptionPlanPolicy() {
    return (c, command) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "PLAN_POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @ConditionalOnMissingBean
  PlanChangeDependencies.FreeAuthority freePlanAdmission(
      org.springframework.beans.factory.ObjectProvider<
              ru.taska.api.b2b.v1.ReactorFreeEligibilityServiceGrpc
                  .ReactorFreeEligibilityServiceStub>
          stubs,
      org.springframework.beans.factory.ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new GrpcFreePlanAdmission(stub, trust, Clock.systemUTC());
    return new PlanChangeDependencies.FreeAuthority() {
      public Mono<PlanChangeDependencies.FreeProof> admit(
          UUID t, UUID op, UUID owner, long version) {
        return Mono.error(
            CatalogErrors.fail(DomainStatus.UNAVAILABLE, "FREE_ADMISSION_UNAVAILABLE"));
      }

      public Mono<PlanChangeDependencies.FreeProof> accepted(
          UUID t, UUID op, UUID owner, long version) {
        return Mono.error(
            CatalogErrors.fail(DomainStatus.UNAVAILABLE, "FREE_ADMISSION_UNAVAILABLE"));
      }

      public Mono<PlanChangeDependencies.FreeProof> cancel(
          UUID t, UUID op, UUID owner, long version) {
        return Mono.error(
            CatalogErrors.fail(DomainStatus.UNAVAILABLE, "FREE_ADMISSION_UNAVAILABLE"));
      }
    };
  }

  @Bean
  SubscriptionPlanChanges subscriptionPlanChanges(
      SubscriptionStore subscriptions,
      PlanChangeDependencies.Policy policy,
      PlanChangeDependencies.FreeAuthority free) {
    return new SubscriptionPlanChanges(subscriptions, policy, free, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  EntitlementStore.ReadAuthority entitlementReadAuthority() {
    return context ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  EntitlementStore entitlements(ConnectionFactory factory, EntitlementStore.ReadAuthority readers) {
    return new EntitlementStore(new TenantTransactionExecutor(factory), readers, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  InvoiceDependencies.ObligationAuthority invoiceObligationAuthority(
      PlanChangeDependencies.Policy policy) {
    return (db, tenant, request, now) ->
        request.getPurpose()
                == ru.taska.api.b2b.v1.CreateInvoiceRequest.PurposeValue.PURPOSE_PLAN_CHANGE
            ? PlanChangeObligations.resolve(db, tenant, request, policy)
            : Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  InvoiceDependencies.CommercialPolicy invoiceCommercialPolicy() {
    return (db, plan, request, now) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "billing.renewal.enabled",
      havingValue = "true")
  RenewalScheduler renewalScheduler(
      InvoiceStore invoices,
      RenewalScheduler.WorkSource jobs,
      RenewalScheduler.ExecutionContext contexts) {
    return new RenewalScheduler(invoices, jobs, contexts, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  InvoiceDependencies.RenewalAuthority renewalAuthority() {
    return (db, context, period, payload) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  InvoiceStore invoices(
      ConnectionFactory factory,
      FinancialReadAuthority readers,
      InvoiceDependencies.ObligationAuthority obligations,
      InvoiceDependencies.CommercialPolicy commercial,
      BillingCatalog catalog,
      @Value("${BILLING_CATALOG_CURSOR_KEY:}") String key,
      InvoiceDependencies.RenewalAuthority renewalAuthority) {
    var clock = Clock.systemUTC();
    return new InvoiceStore(
        new TenantTransactionExecutor(factory),
        readers,
        obligations,
        commercial,
        catalog,
        clock,
        new PeriodCursor(key, clock),
        renewalAuthority);
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.billing.commercial.CommercialDependencies.ConfigurationSource
      commercialConfigurationSource(
          ObjectProvider<
                  ru.taska.api.b2b.v1.ReactorMerchantConfigurationServiceGrpc
                      .ReactorMerchantConfigurationServiceStub>
              stubs,
          ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new ru.taska.billing.commercial.GrpcMerchantConfigurationSource(
          stub, trust, Clock.systemUTC());
    return new ru.taska.billing.commercial.CommercialDependencies.ConfigurationSource() {
      public Mono<ru.taska.api.b2b.v1.MerchantConfigurationSnapshot> current() {
        return Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
      }

      public Mono<ru.taska.api.b2b.v1.MerchantConfigurationSnapshot> version(String version) {
        return current();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.billing.commercial.CommercialDependencies.CredentialAuthority
      commercialCredentialAuthority() {
    return stripe -> Mono.empty();
  }

  @Bean
  @ConditionalOnMissingBean
  ru.taska.billing.commercial.CommercialDependencies.FxQuoteProvider commercialFxQuoteProvider() {
    return (environment, policy) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  ru.taska.billing.commercial.CommercialReadiness commercialReadiness(
      ru.taska.billing.commercial.CommercialDependencies.ConfigurationSource profiles,
      ru.taska.billing.commercial.CommercialDependencies.CredentialAuthority credentials,
      ru.taska.billing.commercial.CommercialDependencies.FxQuoteProvider quotes) {
    return new ru.taska.billing.commercial.CommercialReadiness(
        profiles, credentials, quotes, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  FinancialLedgerDependencies.EvidenceAuthority financialEvidenceAuthority() {
    return (db, tenant, evidence) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  FinancialLedgerDependencies.EligibilityAuthority financialEligibilityAuthority() {
    return (db, tenant, invoice, matches) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  FinancialLedger financialLedger(
      ConnectionFactory factory,
      FinancialReadAuthority readers,
      FinancialLedgerDependencies.EvidenceAuthority evidence,
      FinancialLedgerDependencies.EligibilityAuthority eligibility) {
    return new FinancialLedger(
        new TenantTransactionExecutor(factory), readers, evidence, eligibility, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  PaymentSettlementDependencies.EvidenceAuthority verifiedPaymentEvidence() {
    return new ru.taska.billing.ingress.ProviderPaymentEvidence();
  }

  @Bean
  @ConditionalOnMissingBean
  PaymentSettlementDependencies.TenantAuthority paymentTenantAuthority(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorTenantPaymentStateServiceGrpc
                  .ReactorTenantPaymentStateServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new GrpcTenantPaymentState(stub, trust, Clock.systemUTC());
    return tenant ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  PaymentSettlementDependencies.InitialPaymentDelivery initialPaymentDelivery(
      ObjectProvider<
              ru.taska.api.b2b.v1.ReactorTenantCreationLifecycleServiceGrpc
                  .ReactorTenantCreationLifecycleServiceStub>
          stubs,
      ObjectProvider<RpcContextSecurity> security) {
    var stub = stubs.getIfAvailable();
    var trust = security.getIfAvailable();
    if (stub != null && trust != null)
      return new GrpcInitialPaymentDelivery(stub, trust, Clock.systemUTC());
    return (tenant, decision) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  PaymentSettlement paymentSettlement(
      SubscriptionStore subscriptions,
      FinancialLedger ledger,
      PaymentSettlementDependencies.EvidenceAuthority evidence,
      PaymentSettlementDependencies.TenantAuthority tenants,
      PaymentSettlementDependencies.InitialPaymentDelivery delivery) {
    return new PaymentSettlement(
        subscriptions, ledger, evidence, tenants, delivery, Clock.systemUTC());
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
              Map.entry(
                  ru.taska.api.b2b.v1.ProviderInboxServiceGrpc.getObserveProviderWebhookMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              Map.entry(
                  ru.taska.api.b2b.v1.ProviderInboxServiceGrpc.getRegisterProviderPaymentMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  ru.taska.api.b2b.v1.ProviderInboxServiceGrpc.getProcessProviderWebhookMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  ru.taska.api.b2b.v1.PaymentSettlementServiceGrpc.getApplyVerifiedPaymentMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  ru.taska.api.b2b.v1.PaymentSettlementServiceGrpc
                      .getGetInitialPaymentDecisionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  BillingServiceGrpc.getGetEntitlementMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  ru.taska.api.b2b.v1.EntitlementServiceGrpc.getGetEntitlementSnapshotMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  ru.taska.api.b2b.v1.BillingProvisioningServiceGrpc
                      .getInitializeSubscriptionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  BillingServiceGrpc.getListInvoicesMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  BillingServiceGrpc.getGetInvoiceMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  BillingServiceGrpc.getCreateInvoiceMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  BillingServiceGrpc.getChangeSubscriptionPlanMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  BillingServiceGrpc.getCancelSubscriptionRenewalMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  BillingServiceGrpc.getResumeSubscriptionRenewalMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true)),
              Map.entry(
                  BillingServiceGrpc.getGetSubscriptionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  BillingServiceGrpc.getListSubscriptionPeriodsMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
              Map.entry(
                  BillingServiceGrpc.getResolvePublishedPlanMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              Map.entry(
                  BillingServiceGrpc.getListBillingPlansMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false)),
              Map.entry(
                  BillingServiceGrpc.getCreatePlanVersionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
              Map.entry(
                  BillingServiceGrpc.getPublishPlanVersionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true))),
          environment);
    // No permissive bootstrap. Keys/replay/online-authority adapters must be explicitly supplied.
    return new ServerInterceptor() {
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        call.close(
            Status.UNAVAILABLE.withDescription("Billing trust provider unavailable"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
    };
  }
}
