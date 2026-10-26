package ru.taska.security.oauth;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("oauth-bridge")
public record OAuthBridgeProperties(
    @DefaultValue("false") boolean enabled,
    URI issuer,
    URI resource,
    URI introspectionUri,
    URI grantValidationUri,
    @DefaultValue("auth-service") String introspectionClientId,
    @DefaultValue("") String introspectionSecret,
    @DefaultValue("") String identityServiceCredential,
    @DefaultValue("") String mcpExchangeCredential,
    @DefaultValue("") String delegationSigningSecret) {
  public OAuthBridgeProperties {
    if (enabled) {
      requireHttps(issuer);
      requireHttps(resource);
      requireEndpoint(introspectionUri);
      requireEndpoint(grantValidationUri);
      if (introspectionSecret == null
          || identityServiceCredential == null
          || mcpExchangeCredential == null
          || delegationSigningSecret == null
          || introspectionSecret.length() < 32
          || identityServiceCredential.length() < 32
          || mcpExchangeCredential.length() < 32
          || delegationSigningSecret.length() < 32
          || new java.util.HashSet<>(
                      java.util.List.of(
                          introspectionSecret,
                          identityServiceCredential,
                          mcpExchangeCredential,
                          delegationSigningSecret))
                  .size()
              != 4)
        throw new IllegalArgumentException("Separate OAuth server credentials are required");
    }
  }

  @Override
  public String toString() {
    return "OAuthBridgeProperties[" + "enabled=" + enabled + "]";
  }

  private static void requireHttps(URI uri) {
    if (uri == null
        || !"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null)
      throw new IllegalArgumentException("OAuth issuer/resource must be HTTPS URIs");
  }

  private static void requireEndpoint(URI uri) {
    if (uri == null
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null
        || !("https".equals(uri.getScheme())
            || "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
      throw new IllegalArgumentException(
          "OAuth introspection must use HTTPS (loopback HTTP is test-only)");
  }
}
