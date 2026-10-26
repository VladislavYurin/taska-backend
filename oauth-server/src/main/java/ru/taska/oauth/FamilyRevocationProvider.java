package ru.taska.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.transaction.support.TransactionTemplate;

/** RFC 7009 possession plus client ownership revokes the complete authorization family. */
final class FamilyRevocationProvider implements AuthenticationProvider {
  private final OAuthGrantStore grants;
  private final TransactionTemplate transactions;

  FamilyRevocationProvider(OAuthGrantStore grants, TransactionTemplate transactions) {
    this.grants = grants;
    this.transactions = transactions;
  }

  @Override
  public boolean supports(Class<?> type) {
    return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(type);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var request = (OAuth2TokenRevocationAuthenticationToken) authentication;
    if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()
        || client.getRegisteredClient() == null)
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    String value = request.getToken();
    OAuth2TokenType type =
        value.matches("tsk_oat_[A-Za-z0-9_-]{43}")
            ? OAuth2TokenType.ACCESS_TOKEN
            : value.matches("tsk_ort_[A-Za-z0-9_-]{43}") ? OAuth2TokenType.REFRESH_TOKEN : null;
    if (type == null) return request; // Unknown tokens have the same successful RFC 7009 response.
    return transactions.execute(
        status -> {
          var grant = grants.findByToken(value, type);
          if (grant != null) {
            if (!grant.getRegisteredClientId().equals(client.getRegisteredClient().getId()))
              throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            grants.remove(grant);
          }
          return request;
        });
  }
}
