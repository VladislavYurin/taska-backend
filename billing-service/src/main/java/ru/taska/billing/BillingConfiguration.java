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
      @Value("${BILLING_CATALOG_CURSOR_KEY:}") String cursorKey) {
    var clock = Clock.systemUTC();
    return new SubscriptionStore(
        new TenantTransactionExecutor(factory),
        readers,
        evidence,
        clock,
        new PeriodCursor(cursorKey, clock));
  }

  @Bean
  @ConditionalOnMissingBean
  InvoiceDependencies.ObligationAuthority invoiceObligationAuthority() {
    return (db, tenant, request, now) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  InvoiceDependencies.CommercialPolicy invoiceCommercialPolicy() {
    return (db, plan, request, now) ->
        Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  InvoiceStore invoices(
      ConnectionFactory factory,
      FinancialReadAuthority readers,
      InvoiceDependencies.ObligationAuthority obligations,
      InvoiceDependencies.CommercialPolicy commercial,
      BillingCatalog catalog,
      @Value("${BILLING_CATALOG_CURSOR_KEY:}") String key) {
    var clock = Clock.systemUTC();
    return new InvoiceStore(
        new TenantTransactionExecutor(factory),
        readers,
        obligations,
        commercial,
        catalog,
        clock,
        new PeriodCursor(key, clock));
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
  @GlobalServerInterceptor
  ServerInterceptor registrySecurity(
      ObjectProvider<RpcContextSecurity> provider,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    RpcContextSecurity security = provider.getIfAvailable();
    if (security != null)
      return new TenantServerInterceptor(
          security,
          Map.of(
              ru.taska.api.b2b.v1.BillingProvisioningServiceGrpc.getInitializeSubscriptionMethod()
                      .getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true),
              BillingServiceGrpc.getListInvoicesMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              BillingServiceGrpc.getGetInvoiceMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              BillingServiceGrpc.getCreateInvoiceMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, true),
              BillingServiceGrpc.getGetSubscriptionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              BillingServiceGrpc.getListSubscriptionPeriodsMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              BillingServiceGrpc.getResolvePublishedPlanMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false),
              BillingServiceGrpc.getListBillingPlansMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, false),
              BillingServiceGrpc.getCreatePlanVersionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true),
              BillingServiceGrpc.getPublishPlanVersionMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true)),
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
