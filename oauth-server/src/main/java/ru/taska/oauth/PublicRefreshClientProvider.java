package ru.taska.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

/** Public clients prove refresh-token possession; PKCE remains mandatory for the code exchange. */
final class PublicRefreshClientProvider implements AuthenticationProvider {
  private final OAuthClients clients;

  PublicRefreshClientProvider(OAuthClients clients) {
    this.clients = clients;
  }

  @Override
  public boolean supports(Class<?> type) {
    return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var request = (OAuth2ClientAuthenticationToken) authentication;
    if (!ClientAuthenticationMethod.NONE.equals(request.getClientAuthenticationMethod())
        || !java.util.Set.of("refresh_token", "revoke")
            .contains(request.getAdditionalParameters().get("grant_type"))) return null;
    var client = clients.findByClientId(request.getPrincipal().toString());
    if (client == null
        || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
        || !client
            .getAuthorizationGrantTypes()
            .contains(
                "refresh_token".equals(request.getAdditionalParameters().get("grant_type"))
                    ? AuthorizationGrantType.REFRESH_TOKEN
                    : AuthorizationGrantType.AUTHORIZATION_CODE))
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
  }
}
