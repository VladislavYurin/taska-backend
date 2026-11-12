package ru.taska.oauth;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.stereotype.Component;

/**
 * TAS-498 tenant mode. Every authorization is bound at consent to one USER/ONE_TENANT integration
 * grant approved by a verified tenant web session; code exchange and refresh re-validate it and
 * never change it. Legacy unbound authorizations are refused, never treated as "all companies".
 */
@Component
public class TenantOAuthGrants {
  static final String GRANT = "taska.integration_grant",
      TENANT = "taska.tenant",
      VERSION = "taska.grant_version",
      SCOPES = "taska.grant_scopes";

  /** Immutable binding copied from the authorization attributes. */
  record Binding(UUID grant, UUID tenant, long version) {}

  private final boolean enabled;
  private final TaskaIdentityClient identity;
  private final OAuthClients clients;

  public TenantOAuthGrants(
      @Value("${taska.oauth.tenant-grants:false}") boolean enabled,
      TaskaIdentityClient identity,
      OAuthClients clients) {
    this.enabled = enabled;
    this.identity = identity;
    this.clients = clients;
  }

  public boolean enabled() {
    return enabled;
  }

  /** All three attributes or none; anything else is a corrupted authorization. */
  static Binding binding(OAuth2Authorization authorization) {
    if (authorization == null) return null;
    String grant = authorization.getAttribute(GRANT),
        tenant = authorization.getAttribute(TENANT),
        version = authorization.getAttribute(VERSION);
    if (grant == null && tenant == null && version == null) return null;
    try {
      long parsed = Long.parseLong(version);
      if (parsed < 1) throw new IllegalArgumentException();
      return new Binding(UUID.fromString(grant), UUID.fromString(tenant), parsed);
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Invalid tenant grant binding");
    }
  }

  /**
   * Token endpoint check. Returns false for a definitive refusal, which revokes the family;
   * an identity outage is a retryable error that keeps it.
   */
  boolean live(OAuth2Authorization authorization) {
    Binding binding = binding(authorization);
    if (binding == null) return false;
    var client = clients.findById(authorization.getRegisteredClientId());
    if (client == null) return false;
    try {
      var current =
          identity.validateTenantGrant(
              binding.grant().toString(),
              authorization.getPrincipalName(),
              client.getClientId(),
              binding.version());
      return binding.tenant().toString().equals(current.getTenantId())
          && Long.toString(binding.version()).equals(current.getGrantGeneration());
    } catch (TaskaIdentityClient.TenantGrantRejectedException rejected) {
      return false;
    } catch (AuthenticationServiceException outage) {
      throw unavailable();
    }
  }

  static OAuth2AuthenticationException unavailable() {
    return new OAuth2AuthenticationException(
        new OAuth2Error("temporarily_unavailable", "Identity service is unavailable", null));
  }

  static OAuth2AuthenticationException invalidGrant() {
    return new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
  }
}
