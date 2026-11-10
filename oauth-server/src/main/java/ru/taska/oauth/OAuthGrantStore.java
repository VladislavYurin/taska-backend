package ru.taska.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OAuthGrantStore implements OAuth2AuthorizationService {
  static final String CONSENT_EXPIRES = "taska.consent_expires";
  static final String FAMILY_EXPIRES = "taska.family_expires";
  private final JdbcOAuth2AuthorizationService delegate;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public OAuthGrantStore(JdbcTemplate jdbc, OAuthClients clients, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    delegate = new JdbcOAuth2AuthorizationService(jdbc, clients);
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    String expires = authorization.getAttribute(FAMILY_EXPIRES);
    long familyExpires =
        expires == null
            ? Instant.now().plusSeconds(30L * 86400).getEpochSecond()
            : Long.parseLong(expires);
    if (expires == null)
      authorization =
          OAuth2Authorization.from(authorization)
              .attribute(FAMILY_EXPIRES, Long.toString(familyExpires))
              .build();
    if (authorization.getAttribute(CONSENT_EXPIRES) == null)
      authorization = OAuth2Authorization.from(authorization)
          .attribute(CONSENT_EXPIRES, Long.toString(Instant.now().plusSeconds(600).getEpochSecond())).build();
    var saved = authorization;
    // The row, its binding columns and refresh history commit together or not at all.
    transactions.executeWithoutResult(
        status -> {
          delegate.save(saved);
          var binding = TenantOAuthGrants.binding(saved);
          // TAS-498: queryable copy of the immutable binding; a different grant can never replace it.
          if (binding != null
              && jdbc.update(
                      "UPDATE oauth2_authorization SET integration_grant_id=?,tenant_id=?,grant_version=?"
                          + " WHERE id=? AND (integration_grant_id IS NULL OR (integration_grant_id=?"
                          + " AND tenant_id=? AND grant_version=?))",
                      binding.grant(),
                      binding.tenant(),
                      binding.version(),
                      saved.getId(),
                      binding.grant(),
                      binding.tenant(),
                      binding.version())
                  != 1) throw new IllegalStateException("Tenant grant binding is immutable");
          if (saved.getRefreshToken() != null)
            jdbc.update(
                "INSERT INTO oauth_refresh_history(token_hash,grant_id,family_expires_at)"
                    + " VALUES (?,?,?) ON CONFLICT DO NOTHING",
                hash(saved.getRefreshToken().getToken().getTokenValue()),
                saved.getId(),
                java.sql.Timestamp.from(Instant.ofEpochSecond(familyExpires)));
        });
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    delegate.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    return delegate.findByToken(token, type);
  }

  static boolean pending(OAuth2Authorization authorization) {
    if (authorization == null || authorization.getAccessToken() != null
        || authorization.getRefreshToken() != null
        || authorization.getToken(OAuth2AuthorizationCode.class) != null) return false;
    String expires = authorization.getAttribute(CONSENT_EXPIRES);
    return expires != null && Long.parseLong(expires) > Instant.now().getEpochSecond();
  }

  void revoke(String id) {
    var authorization = findById(id);
    if (authorization != null) remove(authorization);
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
