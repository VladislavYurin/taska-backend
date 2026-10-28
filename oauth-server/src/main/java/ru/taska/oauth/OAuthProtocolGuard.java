package ru.taska.oauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resource is mandatory at each token exchange. Browser consent has independent CSRF protection.
 */
final class OAuthProtocolGuard extends OncePerRequestFilter {
  private final OAuthServerProperties policy;
  private final TaskaIdentityClient identity;
  private final HttpSessionCsrfTokenRepository csrf;

  OAuthProtocolGuard(
      OAuthServerProperties policy,
      TaskaIdentityClient identity,
      HttpSessionCsrfTokenRepository csrf) {
    this.policy = policy;
    this.identity = identity;
    this.csrf = csrf;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if ("/oauth2/token".equals(path)) {
      if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
        error(response, 400, "invalid_request");
        return;
      }
      String[] resource = request.getParameterValues("resource");
      if (resource == null
          || resource.length != 1
          || !policy.resource().toString().equals(resource[0])) {
        error(response, 400, "invalid_target");
        return;
      }
      for (String name :
          new String[] {"grant_type", "client_id", "code", "code_verifier", "refresh_token"}) {
        String[] values = request.getParameterValues(name);
        if (values != null && values.length != 1) {
          error(response, 400, "invalid_request");
          return;
        }
      }
      if ("authorization_code".equals(request.getParameter("grant_type"))
          && (request.getParameter("code_verifier") == null
              || !request.getParameter("code_verifier").matches("[A-Za-z0-9._~-]{43,128}"))) {
        error(response, 400, "invalid_grant");
        return;
      }
    }
    if ("/oauth2/authorize".equals(path) || path.startsWith("/oauth/")) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null
          && authentication.isAuthenticated()
          && !"anonymousUser".equals(authentication.getPrincipal())) {
        try {
          identity.validate(authentication.getName());
        } catch (org.springframework.security.authentication.AuthenticationServiceException failure) {
          browserError(request, response, 503, "temporarily_unavailable");
          return;
        } catch (org.springframework.security.core.AuthenticationException failure) {
          if (request.getSession(false) != null) request.getSession(false).invalidate();
          SecurityContextHolder.clearContext();
          browserError(request, response, 401, "login_required");
          return;
        }
      }
      if ("POST".equals(request.getMethod()) && "/oauth2/authorize".equals(path)) {
        String[] scopes = request.getParameterValues("scope");
        if (scopes != null && scopes.length > 0 && (!java.util.Arrays.asList(scopes).contains("read")
            || java.util.Arrays.stream(scopes).anyMatch(scope -> !java.util.Set.of("read", "write", "offline_access").contains(scope)))) {
          browserError(request, response, 400, "invalid_scope");
          return;
        }
        CsrfToken expected = csrf.loadToken(request);
        String actual = expected == null ? null : request.getHeader(expected.getHeaderName());
        if (actual == null && expected != null)
          actual = request.getParameter(expected.getParameterName());
        if (expected == null
            || actual == null
            || !MessageDigest.isEqual(
                expected.getToken().getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
          browserError(request, response, 403, "invalid_csrf_token");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  static void browserError(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
    if (java.util.Collections.list(request.getHeaders("Accept")).stream().anyMatch(value -> value.contains("text/html")))
      response.sendError(status);
    else error(response, status, code);
  }

  static void error(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write("{\"error\":\"" + code + "\"}");
  }
}
