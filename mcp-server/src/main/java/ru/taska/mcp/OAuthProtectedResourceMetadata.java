package ru.taska.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "taska.mcp.oauth.enabled", havingValue = "true")
class OAuthProtectedResourceMetadata {
  private final McpOAuthProperties properties;

  OAuthProtectedResourceMetadata(McpOAuthProperties properties) {
    this.properties = properties;
  }

  @CrossOrigin(origins = "*", methods = RequestMethod.GET)
  @GetMapping({
    "/.well-known/oauth-protected-resource",
    "/.well-known/oauth-protected-resource/mcp"
  })
  Map<String, Object> metadata() {
    return Map.of(
        "resource",
        properties.resource().toString(),
        "authorization_servers",
        List.of(properties.issuer().toString()),
        "scopes_supported",
        List.of("read", "write"),
        "bearer_methods_supported",
        List.of("header"));
  }
}
