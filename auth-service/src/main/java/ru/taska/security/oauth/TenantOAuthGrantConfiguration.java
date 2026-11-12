package ru.taska.security.oauth;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Activation requires B2B sessions, the RPC trust membership authority and the OAuth bridge. */
@Configuration
@ConditionalOnProperty(name = "taska.b2b.tenant-oauth.enabled", havingValue = "true")
public class TenantOAuthGrantConfiguration {
  @Bean
  TenantOAuthGrantStore tenantOAuthGrantStore(
      DatabaseClient db,
      @Qualifier("transactionalOperator") TransactionalOperator tx,
      OAuthBridgeProperties bridge) {
    if (!bridge.enabled())
      throw new IllegalStateException(
          "The OAuth bridge must be enabled before enabling tenant OAuth grants");
    return new TenantOAuthGrantStore(db, tx, Clock.systemUTC());
  }
}
