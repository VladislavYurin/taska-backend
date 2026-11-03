package ru.taska.tenant;

import io.grpc.*;
import io.r2dbc.spi.ConnectionFactory;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.TenantMembershipServiceGrpc;
import ru.taska.api.b2b.v1.TenantServiceGrpc;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;
import ru.taska.tenant.RegistryDependencies.*;

@Configuration
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
  PublishedPlans publishedPlans() {
    return id ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @ConditionalOnMissingBean
  FreeAdmission freeAdmission() {
    return (db, actor, tenant) ->
        Mono.error(TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  TenantRegistryService tenantRegistry(
      TenantTransactionExecutor transactions,
      PublishedPlans plans,
      FreeAdmission admission,
      @Value("${taska.tenant.creation-window:30d}") Duration window) {
    return new TenantRegistryService(transactions, plans, admission, Clock.systemUTC(), window);
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
              "${taska.tenant.membership-readers:auth-service,api-gateway,project-service,workflow-service,issue-service}")
          java.util.Set<String> readers) {
    return new TenantMembershipService(transactions, cursor, readers);
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
              TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(false, true),
              TenantServiceGrpc.getGetTenantMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              TenantServiceGrpc.getListTenantMembersMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              TenantMembershipServiceGrpc.getGetMembershipMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false),
              TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod().getFullMethodName(),
                  new RpcContextSecurity.Policy(true, false)),
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
