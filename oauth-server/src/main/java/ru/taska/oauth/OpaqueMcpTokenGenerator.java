package ru.taska.oauth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.stereotype.Component;

@Component
public class OpaqueMcpTokenGenerator implements OAuth2TokenGenerator<OAuth2Token> {
  private final SecureRandom random = new SecureRandom();
  private final OAuth2AccessTokenGenerator access = new OAuth2AccessTokenGenerator();

  public OpaqueMcpTokenGenerator(OAuthServerProperties policy) {
    access.setAccessTokenCustomizer(
        context ->
            context
                .getClaims()
                .audience(new ArrayList<>(List.of(policy.resource().toString())))
                .claim("grant_id", context.getAuthorization().getId()));
  }

  @Override
  public OAuth2Token generate(OAuth2TokenContext context) {
    if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
      var generated = access.generate(context);
      if (generated == null || !(generated instanceof ClaimAccessor claims)) return null;
      return new ClaimsToken("tsk_oat_" + random(), generated, claims.getClaims());
    }
    if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
      boolean offline =
          context.getAuthorizedScopes().contains("offline_access")
              || AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())
                  && context.getAuthorization() != null
                  && context.getAuthorization().getAuthorizedScopes().contains("offline_access");
      if (!offline) return null;
      Instant now = Instant.now();
      String family =
          context.getAuthorization() == null
              ? null
              : context.getAuthorization().getAttribute(OAuthGrantStore.FAMILY_EXPIRES);
      Instant expires =
          family == null
              ? now.plusSeconds(30L * 86400)
              : Instant.ofEpochSecond(Long.parseLong(family));
      if (!expires.isAfter(now))
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
      return new OAuth2RefreshToken("tsk_ort_" + random(), now, expires);
    }
    return null;
  }

  private String random() {
    byte[] value = new byte[32];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static final class ClaimsToken extends OAuth2AccessToken implements ClaimAccessor {
    private final Map<String, Object> claims;

    ClaimsToken(String value, OAuth2AccessToken source, Map<String, Object> claims) {
      super(
          TokenType.BEARER, value, source.getIssuedAt(), source.getExpiresAt(), source.getScopes());
      this.claims = new LinkedHashMap<>(claims);
    }

    @Override
    public Map<String, Object> getClaims() {
      return claims;
    }
  }
}
