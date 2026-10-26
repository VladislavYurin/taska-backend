package ru.taska.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.stereotype.Component;

@Component
public class OAuthGrantStore implements OAuth2AuthorizationService {
  static final String FAMILY_EXPIRES = "taska.family_expires";
  private final JdbcOAuth2AuthorizationService delegate;
  private final JdbcTemplate jdbc;

  public OAuthGrantStore(JdbcTemplate jdbc, OAuthClients clients) {
    this.jdbc = jdbc;
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
    delegate.save(authorization);
    if (authorization.getRefreshToken() != null)
      jdbc.update(
          "INSERT INTO oauth_refresh_history(token_hash,grant_id,family_expires_at) VALUES (?,?,?)"
              + " ON CONFLICT DO NOTHING",
          hash(authorization.getRefreshToken().getToken().getTokenValue()),
          authorization.getId(),
          java.sql.Timestamp.from(Instant.ofEpochSecond(familyExpires)));
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
