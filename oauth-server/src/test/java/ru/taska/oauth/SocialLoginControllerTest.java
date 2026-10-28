package ru.taska.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.*;
import org.springframework.web.util.UriComponentsBuilder;
import ru.taska.api.common.v1.*;

class SocialLoginControllerTest {
  final SocialIdentityClient identity = mock(SocialIdentityClient.class);
  final RequestCache requests = mock(RequestCache.class);
  final AuthenticationSuccessHandler success = mock(AuthenticationSuccessHandler.class);
  final HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
  final OAuthServerProperties policy = new OAuthServerProperties(URI.create("https://oauth.example.test"),
      URI.create("https://mcp.example.test/mcp"), List.of(), List.of(), false, 1000, Duration.ofMinutes(5),
      "auth-service", "test-introspection-credential-32-characters", new OAuthServerProperties.Auth("localhost",9090,
      Duration.ofSeconds(2), "test-identity-service-credential-32-characters"));
  final SocialLoginController controller = new SocialLoginController(identity, policy, requests, success, csrf,
      URI.create("https://api.example.test"));
  final MockHttpServletRequest request = new MockHttpServletRequest();
  MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach void prepare() {
    request.setSession(new MockHttpSession());
    when(requests.getRequest(any(), any())).thenReturn(new SimpleSavedRequest("https://oauth.example.test/oauth2/authorize?client_id=registered"));
    when(identity.providers()).thenReturn(List.of("GOOGLE", "GITHUB"));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void startSendsOnlyHashOfPrivateSessionNonceToFixedGateway() throws Exception {
    request.addHeader("Host", "attacker.example");
    controller.start("google", request, response);
    String nonce = (String)request.getSession().getAttribute(SocialLoginController.NONCE);
    assertThat(nonce).matches("[A-Za-z0-9_-]{43}");
    var redirect = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
    assertThat(redirect.getHost()).isEqualTo("api.example.test");
    assertThat(redirect.getPath()).isEqualTo("/api/v1/auth/oauth/google/start");
    assertThat(redirect.getQueryParams().getFirst("target")).isEqualTo("oauth");
    assertThat(redirect.getQueryParams().getFirst("binding")).isEqualTo(SocialLoginController.binding(nonce));
    assertThat(response.getRedirectedUrl()).doesNotContain(nonce);
  }

  @Test void successRotatesSessionClearsCsrfPersistsPrincipalAndResumesOriginalRequest() throws Exception {
    controller.start("github", request, new MockHttpServletResponse());
    String nonce = (String)request.getSession().getAttribute(SocialLoginController.NONCE);
    String before = request.getSession().getId();
    var token = csrf.generateToken(request); csrf.saveToken(token, request, response);
    when(identity.claim("C".repeat(43), nonce)).thenReturn(UserContext.newBuilder()
        .setUserId("d05c5390-7ad0-4acb-b614-307d849f974c").setStatus(UserStatus.USER_STATUS_ACTIVE).build());
    controller.callback("C".repeat(43), null, request, response);
    assertThat(request.getSession().getId()).isNotEqualTo(before);
    assertThat(request.getSession().getAttribute(SocialLoginController.NONCE)).isNull();
    assertThat(csrf.loadToken(request)).isNull();
    var context = (org.springframework.security.core.context.SecurityContext)request.getSession()
        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(context.getAuthentication().getName()).isEqualTo("d05c5390-7ad0-4acb-b614-307d849f974c");
    verify(success).onAuthenticationSuccess(request, response, context.getAuthentication());
  }

  @Test void parallelStartsReuseBrowserBindingAndOnlyOneCallbackCanClaim() throws Exception {
    controller.start("google", request, new MockHttpServletResponse());
    String nonce = (String)request.getSession().getAttribute(SocialLoginController.NONCE);
    controller.start("github", request, new MockHttpServletResponse());
    assertThat(request.getSession().getAttribute(SocialLoginController.NONCE)).isEqualTo(nonce);
    when(identity.claim("C".repeat(43), nonce)).thenReturn(UserContext.newBuilder()
        .setUserId("d05c5390-7ad0-4acb-b614-307d849f974c").setStatus(UserStatus.USER_STATUS_ACTIVE).build());
    controller.callback("C".repeat(43), null, request, response);
    controller.callback("D".repeat(43), null, request, new MockHttpServletResponse());
    verify(identity).claim("C".repeat(43), nonce);
    verify(identity, never()).claim(eq("D".repeat(43)), any());
    verify(success, times(1)).onAuthenticationSuccess(any(), any(), any());
  }

  @Test void foreignBrowserCannotClaimCodeOrEstablishSession() throws Exception {
    controller.callback("C".repeat(43), null, request, response);
    assertThat(response.getRedirectedUrl()).startsWith("https://oauth.example.test/login?error=STATE_INVALID");
    verify(identity, never()).claim(any(), any());
    assertThat(request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
  }

  @Test void unknownAccountExplainsSignupWithoutCreatingSessionAndCannotReplay() throws Exception {
    controller.start("google", request, new MockHttpServletResponse());
    when(identity.claim(any(), any())).thenThrow(new SocialIdentityClient.Failure("SIGNUP_REQUIRED"));
    controller.callback("C".repeat(43), null, request, response);
    assertThat(response.getRedirectedUrl()).contains("error=SIGNUP_REQUIRED");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    response = new MockHttpServletResponse();
    controller.callback("C".repeat(43), null, request, response);
    verify(identity, times(1)).claim(any(), any());
  }

  @Test void providerCancellationIsWhitelistedAndConsumesPendingNonce() throws Exception {
    controller.start("google", request, new MockHttpServletResponse());
    controller.callback(null, "PROVIDER_CANCELLED", request, response);
    assertThat(response.getRedirectedUrl()).contains("error=PROVIDER_CANCELLED");
    assertThat(request.getSession().getAttribute(SocialLoginController.NONCE)).isNull();
    verify(identity, never()).claim(any(), any());
  }

  @Test void directStartAndUnknownProvidersFailWithoutLeavingNonce() throws Exception {
    controller.start("https://attacker.example", request, response);
    assertThat(response.getRedirectedUrl()).contains("error=PROVIDER_UNAVAILABLE");
    response = new MockHttpServletResponse();
    when(requests.getRequest(any(),any())).thenReturn(null);
    controller.start("google", request, response);
    assertThat(response.getRedirectedUrl()).contains("error=STATE_INVALID");
    assertThat(request.getSession().getAttribute(SocialLoginController.NONCE)).isNull();
  }
}
