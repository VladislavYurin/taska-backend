package ru.taska.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.taska.api.auth.oauth.v1.TenantOAuthGrantBinding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** TAS-498 tenant mode: consent binds one tenant grant; code exchange and refresh keep it. */
@SpringBootTest
@AutoConfigureMockMvc
class TenantOAuthGrantProtocolIntegrationTest {
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    DB.start();
  }

  static final String ISSUER = "https://auth.example.test",
      RESOURCE = "https://mcp.example.test/mcp",
      CLIENT = "https://clients.example.test/client.json",
      REDIRECT = "https://client.example.test/callback",
      ACTOR = "d05c5390-7ad0-4acb-b614-307d849f974c",
      SECRET = "fixture-introspection-secret-at-least-32",
      VERIFIER = "A".repeat(43);
  static final String A = "6b0d8ef2-36a7-4c42-9a5d-1b7f2f4e0a01",
      B = "0e3f5c1a-9b2d-4f6e-8a7c-3d5e7f9a1b02";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", DB::getJdbcUrl);
    p.add("spring.datasource.username", DB::getUsername);
    p.add("spring.datasource.password", DB::getPassword);
    p.add("taska.oauth.issuer", () -> ISSUER);
    p.add("taska.oauth.resource", () -> RESOURCE);
    p.add("taska.oauth.metadata-hosts", () -> "clients.example.test");
    p.add("taska.oauth.redirect-uris", () -> REDIRECT);
    p.add("taska.oauth.introspection-client-id", () -> "auth-service");
    p.add("taska.oauth.introspection-secret", () -> SECRET);
    p.add("taska.oauth.auth.service-credential", () -> "fixture-identity-service-credential-32");
    p.add("taska.oauth.tenant-grants", () -> "true");
  }

  @MockitoBean TaskaIdentityClient identity;
  @MockitoBean CimdDocumentFetcher fetcher;
  @Autowired MockMvc mvc;
  @Autowired OAuthClients clients;
  @Autowired OAuthGrantStore grants;
  @Autowired JdbcTemplate jdbc;
  final JsonMapper mapper = JsonMapper.builder().build();

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM oauth_refresh_history");
    jdbc.update("DELETE FROM oauth2_authorization");
    jdbc.update("DELETE FROM oauth2_authorization_consent");
    reset(identity);
    when(fetcher.fetch(any()))
        .thenReturn(
            new CimdDocumentFetcher.Document(
                ("{\"client_id\":\""
                        + CLIENT
                        + "\",\"client_name\":\"Test connector\",\"redirect_uris\":[\""
                        + REDIRECT
                        + "\"],\"grant_types\":[\"authorization_code\",\"refresh_token\"]}")
                    .getBytes(StandardCharsets.UTF_8),
                Duration.ofMinutes(5)));
    assertThat(clients.findByClientId(CLIENT)).isNotNull();
    when(identity.validate(any()))
        .thenReturn(
            ru.taska.api.common.v1.UserContext.newBuilder()
                .setUserId(ACTOR)
                .setEmail("actor@example.invalid")
                .setDisplayName("Actor")
                .setStatus(ru.taska.api.common.v1.UserStatus.USER_STATUS_ACTIVE)
                .build());
  }

  static TenantOAuthGrantBinding binding(String grant, String tenant, String... scopes) {
    return TenantOAuthGrantBinding.newBuilder()
        .setGrantId(grant)
        .setTenantId(tenant)
        .addAllScopes(List.of(scopes))
        .setGrantGeneration("1")
        .build();
  }

  /** The approval auth-service would hold for this pending request. */
  String approved(Pending pending, String tenant, String... scopes) {
    String grant = UUID.randomUUID().toString();
    var result = binding(grant, tenant, scopes);
    when(identity.claimTenantGrant(grant, ACTOR, CLIENT, pending.state)).thenReturn(result);
    when(identity.validateTenantGrant(grant, ACTOR, CLIENT, 1)).thenReturn(result);
    return grant;
  }

  @Test
  void consentIsNeverRememberedAndAllowWithoutTenantGrantIssuesNoCode() throws Exception {
    var pending = pending("read write offline_access");
    assertThat(pending.json.path("tenant_required").asBoolean()).isTrue();
    assertThat(pending.json.path("granted_scopes").size()).isZero();
    for (String grant : new String[] {null, "not-a-grant", UUID.randomUUID().toString().toUpperCase()})
      assertThat(allow(pending, grant, "read", "write").getStatus()).isBetween(400, 499);
    verify(identity, never()).claimTenantGrant(anyString(), anyString(), anyString(), anyString());
    assertNoCode();
  }

  @Test
  void approvedGrantBindsAuthorizationAndTokensCarryOnlyThatTenant() throws Exception {
    var pending = pending("read write offline_access");
    String grant = approved(pending, A, "read", "write");
    var issued = token(code(allow(pending, grant, "read", "write", "offline_access")), 200);
    var row =
        jdbc.queryForMap(
            "SELECT integration_grant_id::text AS grant,tenant_id::text AS tenant,grant_version"
                + " FROM oauth2_authorization");
    assertThat(row.get("grant")).isEqualTo(grant);
    assertThat(row.get("tenant")).isEqualTo(A);
    assertThat(((Number) row.get("grant_version")).longValue()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization_consent", Integer.class))
        .isZero();

    var access = introspect(issued.path("access_token").asText());
    assertThat(access.path("active").asBoolean()).isTrue();
    assertThat(access.path("integration_grant_id").asText()).isEqualTo(grant);
    assertThat(access.path("tenant_id").asText()).isEqualTo(A);
    assertThat(access.path("grant_version").asText()).isEqualTo("1");
    assertThat(access.path("client_id").asText()).isEqualTo(CLIENT);

    var next = refresh(issued.path("refresh_token").asText(), 200);
    var refreshed = introspect(next.path("access_token").asText());
    assertThat(refreshed.path("tenant_id").asText()).isEqualTo(A);
    assertThat(refreshed.path("integration_grant_id").asText()).isEqualTo(grant);
    // Code exchange and refresh each re-validated the bound generation.
    verify(identity, times(2)).validateTenantGrant(grant, ACTOR, CLIENT, 1);

    var listed =
        mapper.readTree(
            mvc.perform(get("/oauth/grants").with(authentication(actor())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listed.get(0).path("tenant_id").asText()).isEqualTo(A);
    var parent =
        mapper.readTree(
            mvc.perform(
                    post("/internal/grants/validate")
                        .with(httpBasic("auth-service", SECRET))
                        .contentType("application/json")
                        .content(
                            mapper.writeValueAsString(
                                Map.of(
                                    "grant_id",
                                    refreshed.path("grant_id").asText(),
                                    "token_id",
                                    refreshed.path("jti").asText()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(parent.path("tenant_id").asText()).isEqualTo(A);
    assertThat(parent.path("integration_grant_id").asText()).isEqualTo(grant);
  }

  @Test
  void consentCannotWidenTheApprovedGrantOrReuseItForAnotherRequest() throws Exception {
    var pending = pending("read write");
    String grant = approved(pending, A, "read");
    assertThat(allow(pending, grant, "read", "write").getStatus()).isBetween(400, 499);
    assertNoCode();

    // The claim is bound to the request it was approved for; a second request is refused.
    var other = pending("read");
    when(identity.claimTenantGrant(grant, ACTOR, CLIENT, other.state))
        .thenThrow(newRejected());
    assertThat(allow(other, grant, "read").getStatus()).isBetween(400, 499);
    assertNoCode();
  }

  @Test
  void identityOutageDuringConsentIssuesNoCode() throws Exception {
    var pending = pending("read");
    String grant = UUID.randomUUID().toString();
    when(identity.claimTenantGrant(grant, ACTOR, CLIENT, pending.state))
        .thenThrow(new AuthenticationServiceException("down"));
    assertThat(allow(pending, grant, "read").getStatus()).isBetween(400, 599);
    assertNoCode();
  }

  @Test
  void grantRejectedAtCodeExchangeRevokesTheAuthorization() throws Exception {
    var pending = pending("read offline_access");
    String grant = approved(pending, A, "read");
    String code = code(allow(pending, grant, "read", "offline_access"));
    when(identity.validateTenantGrant(grant, ACTOR, CLIENT, 1)).thenThrow(newRejected());
    token(code, 400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void revokedGrantNeverRevivesThroughRefreshButAnOutageKeepsTheFamily() throws Exception {
    var pending = pending("read write offline_access");
    String grant = approved(pending, A, "read", "write");
    var issued = token(code(allow(pending, grant, "read", "write", "offline_access")), 200);
    String refresh = issued.path("refresh_token").asText();

    when(identity.validateTenantGrant(grant, ACTOR, CLIENT, 1))
        .thenThrow(new AuthenticationServiceException("down"));
    refresh(refresh, 400);
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean()).isTrue();

    doThrow(newRejected()).when(identity).validateTenantGrant(grant, ACTOR, CLIENT, 1);
    refresh(refresh, 400);
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void unboundLegacyAuthorizationIsRefusedOnRefresh() throws Exception {
    var pending = pending("read offline_access");
    String grant = approved(pending, A, "read");
    var issued = token(code(allow(pending, grant, "read", "offline_access")), 200);
    var authorization =
        grants.findByToken(
            issued.path("refresh_token").asText(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.REFRESH_TOKEN);
    grants.save(
        OAuth2Authorization.from(authorization)
            .attributes(
                values -> {
                  values.remove(TenantOAuthGrants.GRANT);
                  values.remove(TenantOAuthGrants.TENANT);
                  values.remove(TenantOAuthGrants.VERSION);
                })
            .build());
    refresh(issued.path("refresh_token").asText(), 400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void consentForAnotherCompanyIsASeparateConnectionRevokedIndependently() throws Exception {
    var first = pending("read offline_access");
    String grantA = approved(first, A, "read");
    var tokenA = token(code(allow(first, grantA, "read", "offline_access")), 200);
    var second = pending("read offline_access");
    String grantB = approved(second, B, "read");
    var tokenB = token(code(allow(second, grantB, "read", "offline_access")), 200);
    assertThat(introspect(tokenA.path("access_token").asText()).path("tenant_id").asText())
        .isEqualTo(A);
    assertThat(introspect(tokenB.path("access_token").asText()).path("tenant_id").asText())
        .isEqualTo(B);

    var apps =
        mapper.readTree(
            mvc.perform(
                    get("/internal/users/" + ACTOR + "/apps")
                        .with(httpBasic("auth-service", SECRET)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(apps.size()).isEqualTo(2);
    assertThat(List.of(apps.get(0).path("tenantId").asText(), apps.get(1).path("tenantId").asText()))
        .containsExactlyInAnyOrder(A, B);
    String appId = apps.get(0).path("appId").asText();
    assertThat(apps.get(1).path("appId").asText()).isEqualTo(appId);

    mvc.perform(
            delete("/internal/users/" + ACTOR + "/apps/" + appId)
                .queryParam("tenantId", A)
                .with(httpBasic("auth-service", SECRET)))
        .andExpect(status().isNoContent());
    assertThat(introspect(tokenA.path("access_token").asText()).path("active").asBoolean())
        .isFalse();
    assertThat(introspect(tokenB.path("access_token").asText()).path("active").asBoolean())
        .isTrue();
  }

  private void assertNoCode() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE authorization_code_value IS NOT"
                    + " NULL OR integration_grant_id IS NOT NULL",
                Integer.class))
        .isZero();
  }

  private static TaskaIdentityClient.TenantGrantRejectedException newRejected() {
    try {
      var constructor = TaskaIdentityClient.TenantGrantRejectedException.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Pending(MockHttpSession session, String state, JsonNode json) {}

  private Pending pending(String scopes) throws Exception {
    var session = new MockHttpSession();
    var location =
        mvc.perform(
                get("/oauth2/authorize")
                    .session(session)
                    .with(authentication(actor()))
                    .queryParam("response_type", "code")
                    .queryParam("client_id", CLIENT)
                    .queryParam("redirect_uri", REDIRECT)
                    .queryParam("scope", scopes)
                    .queryParam("state", "client-state")
                    .queryParam("resource", RESOURCE)
                    .queryParam("code_challenge", challenge())
                    .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    String state =
        java.net.URLDecoder.decode(
            UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"),
            StandardCharsets.UTF_8);
    var json =
        mapper.readTree(
            mvc.perform(
                    get("/oauth/consent")
                        .session(session)
                        .with(authentication(actor()))
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Pending(session, state, json);
  }

  private MockHttpServletResponse allow(Pending pending, String grant, String... scopes)
      throws Exception {
    var request =
        post("/oauth2/authorize")
            .session(pending.session)
            .with(authentication(actor()))
            .param("client_id", CLIENT)
            .param("state", pending.state)
            .param("scope", scopes)
            .param("decision", "allow")
            .param(
                pending.json.path("csrf_parameter").asText(),
                pending.json.path("csrf_token").asText());
    if (grant != null) request.param("tenant_grant", grant);
    return mvc.perform(request).andReturn().getResponse();
  }

  private static String code(MockHttpServletResponse completed) {
    assertThat(completed.getStatus()).isEqualTo(302);
    assertThat(completed.getRedirectedUrl()).startsWith(REDIRECT).contains("iss=");
    return java.net.URLDecoder.decode(
        UriComponentsBuilder.fromUriString(completed.getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("code"),
        StandardCharsets.UTF_8);
  }

  private JsonNode token(String code, int status) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/oauth2/token")
                    .param("grant_type", "authorization_code")
                    .param("client_id", CLIENT)
                    .param("code", code)
                    .param("redirect_uri", REDIRECT)
                    .param("code_verifier", VERIFIER)
                    .param("resource", RESOURCE))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode refresh(String token, int status) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/oauth2/token")
                    .param("grant_type", "refresh_token")
                    .param("client_id", CLIENT)
                    .param("refresh_token", token)
                    .param("resource", RESOURCE))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode introspect(String token) throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/oauth2/introspect").with(httpBasic("auth-service", SECRET)).param("token", token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private UsernamePasswordAuthenticationToken actor() {
    return UsernamePasswordAuthenticationToken.authenticated(
        ACTOR, null, List.of(new SimpleGrantedAuthority("USER")));
  }

  private String challenge() throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
  }
}
