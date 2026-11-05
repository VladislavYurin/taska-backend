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
    var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
    var owner = new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
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
  public org.springframework.beans.factory.InitializingBean safeProjectRuntime(ConnectionFactory factory) {
    return () -> {
      Boolean safe = DatabaseClient.create(factory).sql("""
          SELECT current_user='taska_project_app' AND NOT
            (rolsuper OR rolinherit OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)
            AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
            AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner=r.oid AND relnamespace='taska'::regnamespace)
            AS safe FROM pg_roles r WHERE rolname=current_user
          """).map((r,m) -> r.get("safe", Boolean.class)).one().block(Duration.ofSeconds(10));
      if (!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Unsafe project runtime database role");
    };
  }

  @Bean
  @DependsOn("safeProjectRuntime")
  TenantProjectStore tenantProjects(ConnectionFactory factory, ObjectMapper json) {
    return new TenantProjectStore(new TenantTransactionExecutor(factory), json);
  }

  /** TAS-340 supplies verified transport and ACL. Old RPC headers cannot mint tenant authority. */
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor projectTenantTransportClosed() {
    return new ServerInterceptor() {
      public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call, Metadata headers,
          ServerCallHandler<Q,S> next) {
        call.close(Status.UNAVAILABLE.withDescription("Project tenant API is not activated"), new Metadata());
        return new ServerCall.Listener<>() {};
      }
    };
  }
}
