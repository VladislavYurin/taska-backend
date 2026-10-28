package ru.taska.oauth;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.csrf.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class SocialLoginController {
  static final String NONCE = SocialLoginController.class.getName() + ".nonce";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> ERRORS = Set.of("STATE_INVALID", "SIGNUP_REQUIRED", "ACCOUNT_BLOCKED", "ACCOUNT_LOCKED",
      "EMAIL_NOT_VERIFIED", "PROVIDER_UNAVAILABLE", "PROVIDER_CANCELLED", "RATE_LIMITED");
  private final SocialIdentityClient identity;
  private final OAuthServerProperties policy;
  private final RequestCache requests;
  private final AuthenticationSuccessHandler success;
  private final URI api;
  private final HttpSessionCsrfTokenRepository csrf;

  SocialLoginController(SocialIdentityClient identity, OAuthServerProperties policy, RequestCache requests,
      AuthenticationSuccessHandler success, HttpSessionCsrfTokenRepository csrf,
      @Value("${taska.oauth.api-base-url:https://api.taska.ozero.dev}") URI api) {
    OAuthServerProperties.requireHttps(api);
    if (api.getPath() != null && !api.getPath().isEmpty() && !"/".equals(api.getPath()))
      throw new IllegalArgumentException("OAuth API base must be a canonical HTTPS origin");
    this.identity = identity; this.policy = policy; this.requests = requests; this.success = success; this.api = api; this.csrf = csrf;
  }

  @GetMapping("/login/provider/{provider}")
  void start(@PathVariable String provider, HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (requests.getRequest(request, response) == null || authenticated(request)) {
      failure(response, "STATE_INVALID"); return;
    }
    String selected = provider.toUpperCase(Locale.ROOT);
    if (!identity.providers().contains(selected)) { failure(response, "PROVIDER_UNAVAILABLE"); return; }
    var session = request.getSession();
    String nonce;
    synchronized (session) {
      // Parallel provider starts belong to one pending browser login. The first callback wins.
      if (session.getAttribute(NONCE) instanceof String existing) nonce = existing;
      else {
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        session.setAttribute(NONCE, nonce);
      }
    }
    response.sendRedirect(UriComponentsBuilder.fromUri(api).replacePath("/api/v1/auth/oauth/" + selected.toLowerCase(Locale.ROOT) + "/start")
        .queryParam("target", "oauth").queryParam("binding", binding(nonce)).build().encode().toUriString());
  }

  @GetMapping("/login/provider/callback")
  void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String error,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    var session = request.getSession(false);
    if (authenticated(request)) { failure(response, "STATE_INVALID"); return; }
    String nonce = null;
    if (session != null) synchronized (session) {
      nonce = session.getAttribute(NONCE) instanceof String value ? value : null;
      session.removeAttribute(NONCE);
    }
    if (nonce == null || requests.getRequest(request, response) == null) { failure(response, "STATE_INVALID"); return; }
    if (error != null) { failure(response, ERRORS.contains(error) ? error : "PROVIDER_UNAVAILABLE"); return; }
    if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) { failure(response, "STATE_INVALID"); return; }
    try {
      var user = identity.claim(code, nonce);
      var actor = UsernamePasswordAuthenticationToken.authenticated(user.getUserId(), null,
          List.of(new SimpleGrantedAuthority("USER")));
      new ChangeSessionIdAuthenticationStrategy().onAuthentication(actor, request, response);
      new CsrfAuthenticationStrategy(csrf).onAuthentication(actor, request, response);
      var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(actor);
      SecurityContextHolder.setContext(context);
      new HttpSessionSecurityContextRepository().saveContext(context, request, response);
      success.onAuthenticationSuccess(request, response, actor);
    } catch (SocialIdentityClient.Failure failure) { failure(response, failure.reason); }
  }

  private boolean authenticated(HttpServletRequest request) { return request.getUserPrincipal() != null; }

  private void failure(HttpServletResponse response, String reason) throws IOException {
    response.sendRedirect(UriComponentsBuilder.fromUriString(policy.issuer() + "/login")
        .queryParam("error", reason).queryParam("request_id", OAuthRequestCorrelation.current()).build().encode().toUriString());
  }

  static String binding(String nonce) {
    try { return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.US_ASCII))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
