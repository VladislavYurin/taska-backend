package ru.taska.oauth;

import java.util.HashSet;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;

/**
 * TAS-498: an approving consent must name the integration grant that the user's tenant web
 * session approved for exactly this pending request. The grant is claimed once and copied into
 * the authorization before the code is issued; the posted scopes cannot exceed it.
 */
final class TenantGrantConsentProvider implements AuthenticationProvider {
  private static final OAuth2TokenType STATE = new OAuth2TokenType("state");
  private final AuthenticationProvider delegate;
  private final OAuthGrantStore grants;
  private final OAuthClients clients;
  private final TaskaIdentityClient identity;

  TenantGrantConsentProvider(
      AuthenticationProvider delegate,
      OAuthGrantStore grants,
      OAuthClients clients,
      TaskaIdentityClient identity) {
    this.delegate = delegate;
    this.grants = grants;
    this.clients = clients;
    this.identity = identity;
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var posted = (OAuth2AuthorizationConsentAuthenticationToken) authentication;
    // A refusal needs no grant and must never consume one.
    if (!"allow".equals(posted.getAdditionalParameters().get("decision"))
        || posted.getScopes().isEmpty()) return delegate.authenticate(authentication);
    if (!(posted.getAdditionalParameters().get("tenant_grant") instanceof String grant)
        || !grant.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw denied();
    OAuth2Authorization pending = grants.findByToken(posted.getState(), STATE);
    if (!OAuthGrantStore.pending(pending)
        || !(posted.getPrincipal() instanceof Authentication actor)
        || !pending.getPrincipalName().equals(actor.getName())
        || TenantOAuthGrants.binding(pending) != null) throw denied();
    var client = clients.findById(pending.getRegisteredClientId());
    if (client == null || !client.getClientId().equals(posted.getClientId())) throw denied();
    var requested = new HashSet<>(posted.getScopes());
    requested.remove("offline_access");
    final ru.taska.api.auth.oauth.v1.TenantOAuthGrantBinding binding;
    try {
      binding =
          identity.claimTenantGrant(
              grant, pending.getPrincipalName(), client.getClientId(), posted.getState());
    } catch (TaskaIdentityClient.TenantGrantRejectedException rejected) {
      throw denied();
    } catch (AuthenticationServiceException outage) {
      throw TenantOAuthGrants.unavailable();
    }
    if (!binding.getScopesList().containsAll(requested))
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
    grants.save(
        OAuth2Authorization.from(pending)
            .attribute(TenantOAuthGrants.GRANT, binding.getGrantId())
            .attribute(TenantOAuthGrants.TENANT, binding.getTenantId())
            .attribute(TenantOAuthGrants.VERSION, binding.getGrantGeneration())
            .attribute(TenantOAuthGrants.SCOPES, String.join(" ", List.copyOf(binding.getScopesList())))
            .build());
    return delegate.authenticate(authentication);
  }

  private static OAuth2AuthenticationException denied() {
    return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED));
  }
}
