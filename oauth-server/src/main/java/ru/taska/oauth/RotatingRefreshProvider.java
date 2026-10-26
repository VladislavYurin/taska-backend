package ru.taska.oauth;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A replay revokes the entire family in a committed transaction, even when the response is an
 * error.
 */
final class RotatingRefreshProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final OAuthGrantStore grants;

  RotatingRefreshProvider(
      AuthenticationProvider delegate,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      OAuthGrantStore grants) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.grants = grants;
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var request = (OAuth2RefreshTokenAuthenticationToken) authentication;
    var result =
        transactions.execute(
            status -> {
              var history =
                  jdbc.query(
                      "SELECT grant_id,used_at,family_expires_at FROM oauth_refresh_history WHERE"
                          + " token_hash=? FOR UPDATE",
                      (row, n) ->
                          new History(
                              row.getString(1),
                              row.getTimestamp(2),
                              row.getTimestamp(3).toInstant()),
                      OAuthGrantStore.hash(request.getRefreshToken()));
              if (!history.isEmpty()) {
                var parent = history.getFirst();
                var authorization = grants.findById(parent.id);
                var client =
                    (org.springframework.security.oauth2.server.authorization.authentication
                            .OAuth2ClientAuthenticationToken)
                        request.getPrincipal();
                if (authorization == null
                    || !authorization
                        .getRegisteredClientId()
                        .equals(client.getRegisteredClient().getId()))
                  throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                if (parent.used != null || !parent.expires.isAfter(Instant.now())) {
                  grants.revoke(parent.id);
                  return new Result(null, true);
                }
              }
              Authentication success = delegate.authenticate(authentication);
              jdbc.update(
                  "UPDATE oauth_refresh_history SET used_at=now() WHERE token_hash=?",
                  OAuthGrantStore.hash(request.getRefreshToken()));
              return new Result(success, false);
            });
    if (result == null || result.replayed)
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    return result.success;
  }

  private record History(String id, Timestamp used, Instant expires) {}

  private record Result(Authentication success, boolean replayed) {}
}
