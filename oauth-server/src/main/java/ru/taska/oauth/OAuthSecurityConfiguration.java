package ru.taska.oauth;

import java.util.*;
import jakarta.servlet.DispatcherType;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.*;
import org.springframework.security.web.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
public class OAuthSecurityConfiguration {
  @Bean("corsConfigurationSource")
  org.springframework.web.cors.CorsConfigurationSource oauthCors() {
    var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
    var metadata = new org.springframework.web.cors.CorsConfiguration();
    metadata.setAllowedOrigins(List.of("*"));
    metadata.setAllowedMethods(List.of("GET", "OPTIONS"));
    metadata.setAllowedHeaders(List.of("Accept"));
    metadata.setAllowCredentials(false);
    source.registerCorsConfiguration("/.well-known/oauth-authorization-server", metadata);
    var protocol = new org.springframework.web.cors.CorsConfiguration();
    protocol.setAllowedOrigins(List.of("*"));
    protocol.setAllowedMethods(List.of("POST", "OPTIONS"));
    protocol.setAllowedHeaders(List.of("Content-Type", "Accept", "Authorization"));
    protocol.setAllowCredentials(false);
    source.registerCorsConfiguration("/oauth2/token", protocol);
    source.registerCorsConfiguration("/oauth/register", protocol);
    return source;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  TransactionTemplate transactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  OAuth2AuthorizationConsentService consentService(JdbcTemplate jdbc, OAuthClients clients) {
    return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(OAuthServerProperties policy) {
    return AuthorizationServerSettings.builder().issuer(policy.issuer().toString()).build();
  }

  @Bean
  @Order(0)
  SecurityFilterChain privateEndpoints(
      HttpSecurity http, OAuthServerProperties policy, PasswordEncoder encoder) throws Exception {
    AuthenticationManager manager =
        authentication -> {
          String id = authentication.getName(),
              secret = String.valueOf(authentication.getCredentials());
          if (!policy.introspectionClientId().equals(id)
              || !java.security.MessageDigest.isEqual(
                  policy.introspectionSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new BadCredentialsException("Invalid service credential");
          return UsernamePasswordAuthenticationToken.authenticated(
              id, null, List.of(new SimpleGrantedAuthority("INTROSPECT")));
        };
    http.securityMatcher("/internal/**")
        .authenticationManager(manager)
        .authorizeHttpRequests(access -> access.anyRequest().hasAuthority("INTROSPECT"))
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(Customizer.withDefaults());
    headers(http, policy);
    return http.build();
  }

  @Bean
  @Order(1)
  SecurityFilterChain authorizationServer(
      HttpSecurity http,
      OAuthClients clients,
      OAuthGrantStore grants,
      OAuth2AuthorizationConsentService consents,
      OpaqueMcpTokenGenerator tokens,
      AuthorizationServerSettings settings,
      OAuthServerProperties policy,
      TaskaIdentityClient identity,
      HttpSessionCsrfTokenRepository csrf,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      RequestCache cache)
      throws Exception {
    var configurer = new OAuth2AuthorizationServerConfigurer();
    http.cors(Customizer.withDefaults())
        .securityMatcher(configurer.getEndpointsMatcher())
        .with(
            configurer,
            server ->
                server
                    .registeredClientRepository(clients)
                    .authorizationService(grants)
                    .authorizationConsentService(consents)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(tokens)
                    .clientAuthentication(
                        client ->
                            client
                                .authenticationConverter(
                                    request -> {
                                      String grant = request.getParameter("grant_type");
                                      boolean refresh =
                                          "/oauth2/token"
                                                  .equals(
                                                      request
                                                          .getRequestURI()
                                                          .substring(
                                                              request.getContextPath().length()))
                                              && "refresh_token".equals(grant);
                                      boolean revoke =
                                          "/oauth2/revoke"
                                              .equals(
                                                  request
                                                      .getRequestURI()
                                                      .substring(
                                                          request.getContextPath().length()));
                                      if ((!refresh && !revoke)
                                          || request.getHeader("Authorization") != null)
                                        return null;
                                      String[] ids = request.getParameterValues("client_id");
                                      if (ids == null
                                          || ids.length != 1
                                          || ids[0].isBlank()
                                          || request.getParameter("client_secret") != null)
                                        throw new OAuth2AuthenticationException(
                                            OAuth2ErrorCodes.INVALID_CLIENT);
                                      return new OAuth2ClientAuthenticationToken(
                                          ids[0],
                                          ClientAuthenticationMethod.NONE,
                                          null,
                                          Map.of(
                                              "grant_type", refresh ? "refresh_token" : "revoke"));
                                    })
                                .authenticationProviders(
                                    providers ->
                                        providers.addFirst(
                                            new PublicRefreshClientProvider(clients))))
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint
                                .consentPage(policy.issuer() + "/consent")
                                .authenticationProviders(
                                    providers ->
                                        providers.forEach(
                                            provider -> {
                                              if (provider
                                                  instanceof
                                                  OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                      authorization) {
                                                authorization.setAuthenticationValidator(
                                                    OAuth2AuthorizationCodeRequestAuthenticationValidator
                                                        .DEFAULT_REDIRECT_URI_VALIDATOR
                                                        .andThen(
                                                            OAuth2AuthorizationCodeRequestAuthenticationValidator
                                                                .DEFAULT_SCOPE_VALIDATOR)
                                                        .andThen(
                                                            context -> {
                                                              OAuth2AuthorizationCodeRequestAuthenticationToken
                                                                  request =
                                                                      context.getAuthentication();
                                                              var parameters =
                                                                  request.getAdditionalParameters();
                                                              boolean valid =
                                                                  context
                                                                          .getRegisteredClient()
                                                                          .getRedirectUris()
                                                                          .contains(
                                                                              request
                                                                                  .getRedirectUri())
                                                                      && policy
                                                                          .redirectUris()
                                                                          .contains(
                                                                              request
                                                                                  .getRedirectUri())
                                                                      && request
                                                                          .getScopes()
                                                                          .contains("read")
                                                                      && "S256"
                                                                          .equals(
                                                                              parameters.get(
                                                                                  "code_challenge_method"))
                                                                      && parameters.get(
                                                                              "code_challenge")
                                                                          instanceof
                                                                          String challenge
                                                                      && challenge.matches(
                                                                          "[A-Za-z0-9_-]{43}")
                                                                      && policy
                                                                          .resource()
                                                                          .toString()
                                                                          .equals(
                                                                              parameters.get(
                                                                                  "resource"));
                                                              if (!valid)
                                                                throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                                                                    new OAuth2Error(
                                                                        "invalid_request"),
                                                                    request);
                                                            }));
                                              } else if (provider instanceof OAuth2AuthorizationConsentAuthenticationProvider consent) {
                                                consent.setAuthorizationConsentCustomizer(context -> {
                                                  OAuth2AuthorizationConsentAuthenticationToken posted = context.getAuthentication();
                                                  if ("allow".equals(posted.getAdditionalParameters().get("decision"))
                                                      && !posted.getScopes().isEmpty() && !posted.getScopes().contains("read"))
                                                    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
                                                  if (!"allow".equals(posted.getAdditionalParameters().get("decision")) || posted.getScopes().isEmpty())
                                                    context.getAuthorizationConsent().authorities(Set::clear);
                                                });
                                              }
                                            }))
                                .authorizationResponseHandler(
                                    (request, response, authentication) -> {
                                      var code =
                                          (OAuth2AuthorizationCodeRequestAuthenticationToken)
                                              authentication;
                                      var uri =
                                          UriComponentsBuilder.fromUriString(code.getRedirectUri())
                                              .queryParam(
                                                  "code",
                                                  code.getAuthorizationCode().getTokenValue())
                                              .queryParam("iss", policy.issuer().toString());
                                      if (code.getState() != null)
                                        uri.queryParam("state", code.getState());
                                      response.sendRedirect(uri.build().encode().toUriString());
                                    })
                                .errorResponseHandler(
                                    (request, response, failure) -> {
                                      // Never redirect an unvalidated client URI on an error.
                                      if (failure
                                          instanceof
                                          OAuth2AuthorizationCodeRequestAuthenticationException
                                              oauth) {
                                        var failed =
                                            oauth.getAuthorizationCodeRequestAuthentication();
                                        var client =
                                            failed == null
                                                ? null
                                                : clients.findByClientId(failed.getClientId());
                                        if (client != null
                                            && failed.getRedirectUri() != null
                                            && client
                                                .getRedirectUris()
                                                .contains(failed.getRedirectUri())
                                            && policy
                                                .redirectUris()
                                                .contains(failed.getRedirectUri())) {
                                          var uri =
                                              UriComponentsBuilder.fromUriString(
                                                      failed.getRedirectUri())
                                                  .queryParam(
                                                      "error", oauth.getError().getErrorCode())
                                                  .queryParam("iss", policy.issuer().toString());
                                          if (failed.getState() != null)
                                            uri.queryParam("state", failed.getState());
                                          response.sendRedirect(uri.build().encode().toUriString());
                                          return;
                                        }
                                      }
                                      OAuthProtocolGuard.browserError(request, response, 400, "invalid_request");
                                    }))
                    .tokenEndpoint(
                        endpoint ->
                            endpoint.authenticationProviders(
                                providers -> {
                                  for (int i = 0; i < providers.size(); i++)
                                    if (providers.get(i)
                                        instanceof OAuth2AuthorizationCodeAuthenticationProvider)
                                      providers.set(i, new SingleUseCodeProvider(
                                          providers.get(i), jdbc, transactions, grants));
                                  for (int i = 0; i < providers.size(); i++)
                                    if (providers.get(i)
                                        instanceof OAuth2RefreshTokenAuthenticationProvider)
                                      providers.set(
                                          i,
                                          new RotatingRefreshProvider(
                                              providers.get(i), jdbc, transactions, grants));
                                }))
                    .tokenRevocationEndpoint(
                        endpoint ->
                            endpoint.authenticationProviders(
                                providers -> {
                                  for (int i = 0; i < providers.size(); i++)
                                    if (providers.get(i)
                                        instanceof OAuth2TokenRevocationAuthenticationProvider)
                                      providers.set(
                                          i, new FamilyRevocationProvider(grants, transactions));
                                }))
                    .authorizationServerMetadataEndpoint(
                        metadata ->
                            metadata.authorizationServerMetadataCustomizer(
                                builder -> {
                                  builder
                                      .claim("client_id_metadata_document_supported", true)
                                      .claim("authorization_response_iss_parameter_supported", true)
                                      .scopes(
                                          scopes -> {
                                            scopes.clear();
                                            scopes.addAll(
                                                List.of("read", "write", "offline_access"));
                                          })
                                      .codeChallengeMethods(
                                          methods -> {
                                            methods.clear();
                                            methods.add("S256");
                                          });
                                  if (policy.dcrEnabled())
                                    builder.claim(
                                        "registration_endpoint",
                                        policy.issuer() + "/oauth/register");
                                })))
        .authorizeHttpRequests(access -> access.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll().anyRequest().authenticated())
        .requestCache(config -> config.requestCache(cache))
        .exceptionHandling(
            errors ->
                errors.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint(policy.issuer() + "/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML))
                    .defaultAuthenticationEntryPointFor((request, response, failure) -> OAuthProtocolGuard.error(response, 401, "login_required"), jsonMatcher()))
        .addFilterBefore(
            new OAuthProtocolGuard(policy, identity, csrf, grants),
            org.springframework.security.web.authentication.logout.LogoutFilter.class);
    headers(http, policy);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain browser(
      HttpSecurity http,
      OAuthGrantStore grants,
      TaskaIdentityClient identity,
      OAuthServerProperties policy,
      HttpSessionCsrfTokenRepository csrf,
      OAuthBrowserSupport browser,
      RequestCache cache,
      AuthenticationSuccessHandler success)
      throws Exception {
    http.cors(Customizer.withDefaults())
        .authenticationProvider(
            new AuthenticationProvider() {
              @Override
              public boolean supports(Class<?> type) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
              }

              @Override
              public Authentication authenticate(Authentication request) {
                var user =
                    identity.authenticate(
                        request.getName(), String.valueOf(request.getCredentials()));
                return UsernamePasswordAuthenticationToken.authenticated(
                    user.getUserId(), null, List.of(new SimpleGrantedAuthority("USER")));
              }
            })
        .authorizeHttpRequests(
            access ->
                access
                    .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                    .requestMatchers("/login", "/login/**", "/assets/**", "/favicon.ico", "/error", "/actuator/health", "/oauth/register")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(config -> config.requestCache(cache))
        .formLogin(form -> form.loginPage("/login").successHandler(success).failureHandler(browser::loginFailure).permitAll())
        .logout(logout -> logout.addLogoutHandler(browser::rememberLogoutTarget).logoutSuccessHandler(browser::logoutSuccess)
            .deleteCookies("TASKA_OAUTH_SESSION"))
        .exceptionHandling(errors -> errors
            .defaultAuthenticationEntryPointFor((request, response, failure) -> OAuthProtocolGuard.error(response, 401, "login_required"), jsonMatcher())
            .accessDeniedHandler((request, response, failure) -> {
              if ("/login".equals(request.getRequestURI()) && failure instanceof CsrfException)
                response.sendRedirect(policy.issuer() + "/login?error=SESSION_EXPIRED&request_id=" + OAuthRequestCorrelation.current());
              else OAuthProtocolGuard.browserError(request, response, 403, "access_denied");
            }))
        .csrf(
            csrfConfig ->
                csrfConfig.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/oauth/register"))
        .addFilterBefore(new OAuthProtocolGuard(policy, identity, csrf, grants), AuthorizationFilter.class);
    headers(http, policy);
    return http.build();
  }

  private static MediaTypeRequestMatcher jsonMatcher() {
    var matcher = new MediaTypeRequestMatcher(MediaType.APPLICATION_JSON);
    matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
    return matcher;
  }

  private void headers(HttpSecurity http, OAuthServerProperties policy) throws Exception {
    String origins = policy.redirectUris().stream().map(java.net.URI::create)
        .map(uri -> uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort()))
        .distinct().sorted().collect(java.util.stream.Collectors.joining(" "));
    http.headers(
        headers ->
            headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; form-action 'self' " + origins + "; base-uri 'none'"))
                .cacheControl(Customizer.withDefaults()));
  }
}
