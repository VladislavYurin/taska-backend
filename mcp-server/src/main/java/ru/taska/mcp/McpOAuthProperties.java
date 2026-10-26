package ru.taska.mcp;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taska.mcp.oauth")
public record McpOAuthProperties(
    @DefaultValue("false") boolean enabled,
    URI issuer,
    URI resource,
    @DefaultValue("") String exchangeCredential) {
  public McpOAuthProperties {
    if (enabled) {
      for (URI uri : new URI[] {issuer, resource}) {
        if (uri == null
            || !"https".equals(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null
            || uri.getQuery() != null)
          throw new IllegalArgumentException("MCP OAuth requires canonical HTTPS issuer/resource");
      }
      if (exchangeCredential == null || exchangeCredential.length() < 32)
        throw new IllegalArgumentException("MCP OAuth exchange service credential is required");
    }
  }

  public URI metadataUri() {
    return URI.create(
        resource.getScheme()
            + "://"
            + resource.getRawAuthority()
            + "/.well-known/oauth-protected-resource");
  }

  @Override
  public String toString() {
    return "McpOAuthProperties[enabled=" + enabled + "]";
  }

  static McpOAuthProperties disabled() {
    return new McpOAuthProperties(false, null, null, "");
  }
}
