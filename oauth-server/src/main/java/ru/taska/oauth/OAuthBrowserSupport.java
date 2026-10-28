package ru.taska.oauth;

import jakarta.servlet.http.*;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.*;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuthBrowserSupport {
  static final String CONSENT_STATE = OAuthBrowserSupport.class.getName() + ".consentState";
  private static final String LOGOUT_TARGET = OAuthBrowserSupport.class.getName() + ".logoutTarget";
  private final OAuthServerProperties policy;
  private final OAuthGrantStore grants;
  private final OAuthClients clients;

  OAuthBrowserSupport(OAuthServerProperties policy, OAuthGrantStore grants, OAuthClients clients) {
    this.policy = policy; this.grants = grants; this.clients = clients;
  }

  void rememberLogoutTarget(HttpServletRequest request, HttpServletResponse response, Authentication actor) {
    var session = request.getSession(false);
    String[] values = request.getParameterValues("consent_state");
    if (actor == null || session == null || values == null || values.length != 1
        || !values[0].equals(session.getAttribute(CONSENT_STATE))) return;
    var grant = grants.findByToken(values[0], new OAuth2TokenType("state"));
    if (!OAuthGrantStore.pending(grant) || !grant.getPrincipalName().equals(actor.getName())) return;
    OAuth2AuthorizationRequest original = grant.getAttribute(OAuth2AuthorizationRequest.class.getName());
    var client = clients.findById(grant.getRegisteredClientId());
    if (original == null || client == null || !client.getRedirectUris().contains(original.getRedirectUri())
        || !policy.redirectUris().contains(original.getRedirectUri())) return;
    var uri = UriComponentsBuilder.fromUriString(policy.issuer() + "/oauth2/authorize")
        .queryParam("response_type", "code").queryParam("client_id", original.getClientId())
        .queryParam("redirect_uri", original.getRedirectUri())
        .queryParam("scope", String.join(" ", new TreeSet<>(original.getScopes())));
    if (original.getState() != null) uri.queryParam("state", original.getState());
    for (String key : List.of("resource", "code_challenge", "code_challenge_method")) {
      Object value = original.getAdditionalParameters().get(key);
      if (value instanceof String text) uri.queryParam(key, text);
    }
    request.setAttribute(LOGOUT_TARGET, uri.build().encode().toUriString());
  }

  void logoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication actor)
      throws java.io.IOException {
    String target = request.getAttribute(LOGOUT_TARGET) instanceof String value
        ? value : policy.issuer() + "/login?logout";
    response.sendRedirect(target);
  }

  void loginFailure(HttpServletRequest request, HttpServletResponse response,
      org.springframework.security.core.AuthenticationException failure) throws java.io.IOException {
    String code = failure instanceof LockedException ? "ACCOUNT_LOCKED"
        : failure instanceof AuthenticationServiceException ? "SERVICE_UNAVAILABLE" : "INVALID_CREDENTIALS";
    var uri = UriComponentsBuilder.fromUriString(policy.issuer() + "/login").queryParam("error", code)
        .queryParam("request_id", OAuthRequestCorrelation.current());
    if (failure instanceof OAuthAccountLockedException locked && locked.until() != null)
      uri.queryParam("until", locked.until().toString());
    response.sendRedirect(uri.build().encode().toUriString());
  }

  @Configuration(proxyBeanMethods = false)
  static class Requests {
    @Bean
    RequestCache oauthRequestCache(OAuthServerProperties policy) {
      return new HttpSessionRequestCache() {
        { setRequestMatcher(request -> "GET".equals(request.getMethod())
            && "/oauth2/authorize".equals(request.getRequestURI().substring(request.getContextPath().length()))); }
        @Override public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
          URI issuer = policy.issuer();
          super.saveRequest(new HttpServletRequestWrapper(request) {
            @Override public String getScheme() { return issuer.getScheme(); }
            @Override public String getServerName() { return issuer.getHost(); }
            @Override public int getServerPort() { return issuer.getPort() == -1 ? 443 : issuer.getPort(); }
            @Override public StringBuffer getRequestURL() { return new StringBuffer(policy.issuer() + getRequestURI()); }
          }, response);
        }
      };
    }

    @Bean
    AuthenticationSuccessHandler oauthLoginSuccess(RequestCache cache, OAuthServerProperties policy) {
      return (request, response, authentication) -> {
        var saved = cache.getRequest(request, response);
        cache.removeRequest(request, response);
        response.sendRedirect(saved == null ? policy.issuer() + "/login?done" : saved.getRedirectUrl());
      };
    }
  }
}
