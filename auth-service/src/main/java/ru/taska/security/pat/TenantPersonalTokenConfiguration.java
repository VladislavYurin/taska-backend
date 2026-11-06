package ru.taska.security.pat;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import ru.taska.capability.CapabilityEvaluator;
import tools.jackson.databind.ObjectMapper;

/** Activation requires a current-facts capability source adapter and the B2B receipt key. */
@Configuration
@ConditionalOnProperty(name = "taska.b2b.tenant-tokens.enabled", havingValue = "true")
public class TenantPersonalTokenConfiguration {
  @Bean
  TenantPersonalTokenStore tenantPersonalTokenStore(
      DatabaseClient db,
      @Qualifier("transactionalOperator") TransactionalOperator tx,
      PersonalTokenProperties policy,
      ObjectMapper json,
      @Value("${taska.b2b.sessions.refresh-receipt-key:}") String encodedReceiptKey) {
    if (encodedReceiptKey == null || encodedReceiptKey.isBlank())
      throw new IllegalStateException(
          "TASKA_B2B_REFRESH_RECEIPT_KEY must be configured before enabling tenant tokens");
    byte[] receiptKey;
    try {
      receiptKey = Base64.getDecoder().decode(encodedReceiptKey);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("TASKA_B2B_REFRESH_RECEIPT_KEY must be valid Base64", error);
    }
    if (receiptKey.length != 32)
      throw new IllegalStateException("TASKA_B2B_REFRESH_RECEIPT_KEY must contain 32 bytes");
    return new TenantPersonalTokenStore(
        db, tx, Clock.systemUTC(), new SecureRandom(), policy, receiptKey, json);
  }

  @Bean
  TenantPersonalTokenAdmission tenantPersonalTokenAdmission(CapabilityEvaluator.Source facts) {
    return new TenantPersonalTokenAdmission(new CapabilityEvaluator(facts, Clock.systemUTC()));
  }
}
