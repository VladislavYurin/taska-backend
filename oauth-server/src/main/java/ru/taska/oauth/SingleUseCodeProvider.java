package ru.taska.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Serialize code redemption across replicas; a replay commits revocation before returning an error. */
final class SingleUseCodeProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final OAuthGrantStore grants;

  SingleUseCodeProvider(AuthenticationProvider delegate, JdbcTemplate jdbc,
      TransactionTemplate transactions, OAuthGrantStore grants) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.grants = grants;
  }

  @Override
  public boolean supports(Class<?> type) { return delegate.supports(type); }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var request = (OAuth2AuthorizationCodeAuthenticationToken) authentication;
    var outcome = transactions.execute(status -> {
      var ids = jdbc.query("SELECT id FROM oauth2_authorization WHERE authorization_code_value=? FOR UPDATE",
          (row, index) -> row.getString(1), request.getCode());
      if (ids.isEmpty()) return new Outcome(null, true);
      var grant = grants.findById(ids.getFirst());
      var client = (OAuth2ClientAuthenticationToken) request.getPrincipal();
      if (grant == null || client.getRegisteredClient() == null
          || !grant.getRegisteredClientId().equals(client.getRegisteredClient().getId()))
        return new Outcome(null, true);
      var code = grant.getToken(OAuth2AuthorizationCode.class);
      if (code == null || !code.isActive()) {
        grants.revoke(grant.getId());
        return new Outcome(null, true);
      }
      return new Outcome(delegate.authenticate(authentication), false);
    });
    if (outcome == null || outcome.invalid())
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    return outcome.success();
  }

  private record Outcome(Authentication success, boolean invalid) { }
}
