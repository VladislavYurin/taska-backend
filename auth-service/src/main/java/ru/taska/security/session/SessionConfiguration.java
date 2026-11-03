package ru.taska.security.session;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Activation requires a trusted membership authority and explicitly provisioned keys. */
@Configuration
@ConditionalOnProperty(name = "taska.b2b.sessions.enabled", havingValue = "true")
public class SessionConfiguration {
  @Bean
  WebSessionStore webSessionStore(DatabaseClient db,
      @Qualifier("transactionalOperator") TransactionalOperator tx,
      WebSessionStore.MembershipAuthority membership,
      @Value("${taska.b2b.sessions.refresh-receipt-key:}") String encodedReceiptKey,
      @Value("${taska.b2b.sessions.refresh-ttl:7d}") Duration refreshTtl) {
    byte[] receiptKey = Base64.getDecoder().decode(encodedReceiptKey);
    return new WebSessionStore(db, tx, Clock.systemUTC(), new SecureRandom(),
        refreshTtl, membership, receiptKey);
  }

  @Bean
  TenantAccessIssuer tenantAccessIssuer(
      @Value("${taska.b2b.sessions.ed25519-private-key:}") String encodedPrivateKey,
      @Value("${taska.b2b.sessions.ed25519-kid:}") String kid,
      @Value("${taska.b2b.sessions.access-ttl:15m}") Duration accessTtl)
      throws GeneralSecurityException {
    byte[] der = Base64.getDecoder().decode(encodedPrivateKey);
    PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(der));
    return new TenantAccessIssuer(privateKey, kid, Clock.systemUTC(), accessTtl);
  }
}
