package ru.taska.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** AS-origin contracts for TAS-414. No client-supplied actor or grant ownership is trusted. */
@RestController
public class OAuthBackendController {
  private final OAuthClients clients;
  private final OAuthGrantStore grants;
  private final OAuth2AuthorizationConsentService consents;
  private final OAuthServerProperties policy;
  private final JdbcTemplate jdbc;
  private final HttpSessionCsrfTokenRepository csrf;
  private final TaskaIdentityClient identity;
  private long registrationWindow;
  private int registrations;

  public OAuthBackendController(
      OAuthClients clients,
      OAuthGrantStore grants,
      OAuth2AuthorizationConsentService consents,
      OAuthServerProperties policy,
      JdbcTemplate jdbc,
      HttpSessionCsrfTokenRepository csrf, TaskaIdentityClient identity) {
    this.clients = clients;
    this.grants = grants;
    this.consents = consents;
    this.policy = policy;
    this.jdbc = jdbc;
    this.csrf = csrf;
    this.identity = identity;
  }

  @GetMapping("/oauth/consent")
  public Map<String, Object> consent(
      @RequestParam String state,
      Authentication actor,
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    if (state.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    var authorization = grants.findByToken(state, new OAuth2TokenType("state"));
    if (!OAuthGrantStore.pending(authorization) || !authorization.getPrincipalName().equals(actor.getName()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    OAuth2AuthorizationRequest original =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (original == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var client = clients.findById(authorization.getRegisteredClientId());
    if (client == null || !client.getRedirectUris().contains(original.getRedirectUri())
        || !policy.redirectUris().contains(original.getRedirectUri()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    CsrfToken token = csrf.loadDeferredToken(request, response).get();
    var user = identity.validate(actor.getName());
    var consent = consents.findById(client.getId(), actor.getName());
    var redirect = java.net.URI.create(original.getRedirectUri());
    if (redirect.getHost() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    String host = redirect.getHost().toLowerCase(Locale.ROOT);
    if (redirect.getPort() != -1) host += ":" + redirect.getPort();
    var values = new LinkedHashMap<String, Object>();
    values.put("client_id", client.getClientId());
    values.put("client_name", client.getClientName());
    values.put("client_host", host);
    String metadataHost = client.getClientId().startsWith("https://")
        ? java.net.URI.create(client.getClientId()).getHost() : null;
    values.put("metadata_host", metadataHost == null ? null : metadataHost.toLowerCase(Locale.ROOT));
    values.put("redirect_kind", "http".equals(redirect.getScheme()) ? "loopback" : "web");
    values.put("scopes", new TreeSet<>(original.getScopes()));
    values.put("granted_scopes", consent == null ? Set.of() : new TreeSet<>(consent.getScopes()));
    values.put("account", Map.of("email", user.getEmail(), "display_name", user.getDisplayName()));
    values.put("state", state);
    values.put("submit_uri", "/oauth2/authorize");
    values.put("csrf_parameter", token.getParameterName());
    values.put("csrf_token", token.getToken());
    request.getSession().setAttribute(OAuthBrowserSupport.CONSENT_STATE, state);
    return values;
  }

  @GetMapping("/oauth/grants")
  public List<Map<String, Object>> grants(Authentication actor) {
    return jdbc.query(
        "SELECT id,registered_client_id,authorized_scopes FROM oauth2_authorization WHERE"
            + " principal_name=? AND access_token_value IS NOT NULL AND"
            + " ((access_token_expires_at>now() AND"
            + " COALESCE(access_token_metadata::jsonb->>'metadata.token.invalidated','false')='false')"
            + " OR (refresh_token_value IS NOT NULL AND refresh_token_expires_at>now() AND"
            + " COALESCE(refresh_token_metadata::jsonb->>'metadata.token.invalidated','false')='false'))"
            + " ORDER BY id",
        (row, n) -> {
          var client = clients.findById(row.getString(2));
          return Map.<String, Object>of(
              "grant_id",
              row.getString(1),
              "client_id",
              client.getClientId(),
              "client_name",
              client.getClientName(),
              "scopes",
              Arrays.asList(row.getString(3).split(",")));
        },
        actor.getName());
  }

  @DeleteMapping("/oauth/grants/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable String id, Authentication actor) {
    var grant = grants.findById(id);
    if (grant == null || !grant.getPrincipalName().equals(actor.getName()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    grants.remove(grant);
    var consent = consents.findById(grant.getRegisteredClientId(), actor.getName());
    if (consent != null) consents.remove(consent);
  }

  @PostMapping("/internal/grants/validate")
  public Map<String, Object> validate(@RequestBody ParentRequest request) {
    if (request.grant_id == null
        || request.token_id == null
        || request.grant_id.length() > 100
        || request.token_id.length() > 100) return Map.of("active", false);
    var grant = grants.findById(request.grant_id);
    var access = grant == null ? null : grant.getAccessToken();
    if (access == null || !access.isActive()) return Map.of("active", false);
    Map<String, Object> claims = access.getClaims();
    if (claims == null || !request.token_id.equals(claims.get("jti")))
      return Map.of("active", false);
    return Map.of(
        "active",
        true,
        "sub",
        grant.getPrincipalName(),
        "iss",
        policy.issuer().toString(),
        "aud",
        List.of(policy.resource().toString()),
        "exp",
        access.getToken().getExpiresAt().getEpochSecond(),
        "scope",
        String.join(" ", access.getToken().getScopes()),
        "grant_id",
        grant.getId(),
        "jti",
        request.token_id);
  }

  public record ParentRequest(String grant_id, String token_id) {}

  @PostMapping("/oauth/register")
  public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode metadata) {
    if (!policy.dcrEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    synchronized (this) {
      long now = Instant.now().getEpochSecond() / 60;
      if (now != registrationWindow) {
        registrationWindow = now;
        registrations = 0;
      }
      if (++registrations > 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
    try {
      var client = clients.registerDcr(metadata);
      return ResponseEntity.status(HttpStatus.CREATED)
          .cacheControl(CacheControl.noStore())
          .body(
              Map.of(
                  "client_id",
                  client.getClientId(),
                  "client_name",
                  client.getClientName(),
                  "redirect_uris",
                  client.getRedirectUris(),
                  "token_endpoint_auth_method",
                  "none",
                  "grant_types",
                  client.getAuthorizationGrantTypes().stream().map(g -> g.getValue()).toList(),
                  "response_types",
                  List.of("code")));
    } catch (org.springframework.security.oauth2.core.OAuth2AuthenticationException failure) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid client metadata");
    }
  }
}
