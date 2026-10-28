package ru.taska.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taska.oauth")
public record OAuthServerProperties(
    URI issuer,
    URI resource,
    @DefaultValue List<String> metadataHosts,
    @DefaultValue List<String> redirectUris,
    @DefaultValue("false") boolean dcrEnabled,
    @DefaultValue("1000") int maxClients,
    @DefaultValue("5m") Duration metadataTtl,
    String introspectionClientId,
    String introspectionSecret,
    Auth auth) {
  public OAuthServerProperties {
    requireHttps(issuer);
    if (!issuer.getRawPath().isEmpty()) throw new IllegalArgumentException("OAuth issuer must be an origin without a path or trailing slash");
    requireHttps(resource);
    metadataHosts = metadataHosts == null ? List.of() : List.copyOf(metadataHosts);
    redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
    if (maxClients < 1
        || maxClients > 100000
        || metadataTtl == null
        || metadataTtl.isNegative()
        || metadataTtl.isZero()
        || metadataTtl.compareTo(Duration.ofMinutes(5)) > 0
        || introspectionClientId == null
        || !introspectionClientId.matches("[A-Za-z0-9_-]{1,64}")
        || introspectionSecret == null
        || introspectionSecret.length() < 32
        || introspectionSecret.length() > 256
        || auth == null) throw new IllegalArgumentException("Invalid OAuth server policy");
    for (String host : metadataHosts) {
      if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))
        throw new IllegalArgumentException("CIMD hosts must be exact lowercase hostnames");
    }
    for (String redirect : redirectUris) validateRedirect(redirect);
  }

  @Override
  public String toString() {
    return "OAuthServerProperties[issuer=" + issuer + ", resource=" + resource + "]";
  }

  static void requireHttps(URI value) {
    if (value == null
        || !"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getFragment() != null
        || value.getQuery() != null)
      throw new IllegalArgumentException("OAuth issuer/resource must be canonical HTTPS URIs");
  }

  static void validateRedirect(String value) {
    URI uri = URI.create(value);
    boolean loopback =
        "http".equals(uri.getScheme())
            && ("127.0.0.1".equals(uri.getHost())
                || "localhost".equals(uri.getHost())
                || "[::1]".equals(uri.getHost()));
    if (!("https".equals(uri.getScheme()) || loopback)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || value.contains("*")
        || value.length() > 2048)
      throw new IllegalArgumentException("OAuth redirect URI must be exact HTTPS or loopback HTTP");
  }

  public record Auth(
      @DefaultValue("auth-service") String host,
      @DefaultValue("9090") int port,
      @DefaultValue("2s") Duration timeout,
      String serviceCredential) {
    @Override
    public String toString() {
      return "OAuthIdentityTransport[host=" + host + ", port=" + port + "]";
    }

    public Auth {
      if (host == null
          || host.isBlank()
          || port < 1
          || port > 65535
          || timeout == null
          || timeout.isZero()
          || timeout.isNegative()
          || serviceCredential == null
          || serviceCredential.length() < 32)
        throw new IllegalArgumentException("OAuth identity service credential is required");
    }
  }
}
