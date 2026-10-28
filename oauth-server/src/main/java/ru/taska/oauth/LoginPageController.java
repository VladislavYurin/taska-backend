package ru.taska.oauth;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

@RestController
public class LoginPageController {
  private final HttpSessionCsrfTokenRepository csrf;
  private final RequestCache requests;
  private final SocialIdentityClient social;
  LoginPageController(HttpSessionCsrfTokenRepository csrf, RequestCache requests, SocialIdentityClient social) {
    this.csrf = csrf; this.requests = requests; this.social = social;
  }

  @GetMapping("/login/context")
  public Map<String, Object> context(HttpServletRequest request, HttpServletResponse response) {
    var token = csrf.loadDeferredToken(request, response).get();
    return Map.of("csrf_parameter", token.getParameterName(), "csrf_token", token.getToken(),
        "pending_request", requests.getRequest(request, response) != null, "providers", social.providers());
  }

  @Configuration(proxyBeanMethods = false)
  static class Pages implements WebMvcConfigurer {
    @Override public void addViewControllers(ViewControllerRegistry registry) {
      registry.addViewController("/login").setViewName("forward:/login.html");
      registry.addViewController("/consent").setViewName("forward:/consent.html");
    }
  }
}
