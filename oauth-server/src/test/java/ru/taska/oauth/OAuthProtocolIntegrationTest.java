package ru.taska.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
class OAuthProtocolIntegrationTest {
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    DB.start();
  }

  static final String ISSUER = "https://auth.example.test",
      RESOURCE = "https://mcp.example.test/mcp";
  static final String CLIENT = "https://clients.example.test/client.json",
      REDIRECT = "https://client.example.test/callback";
  static final String ACTOR = "d05c5390-7ad0-4acb-b614-307d849f974c",
      SECRET = "fixture-introspection-secret-at-least-32";
  static final String VERIFIER = "A".repeat(43);

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
    p.add("taska.oauth.dcr-enabled", () -> "true");
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
  }

  @Test
  void metadataHasFixedIssuerCimdAndS256() throws Exception {
    mvc.perform(get("/.well-known/oauth-authorization-server").header("Host", "evil.example"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issuer").value(ISSUER))
        .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
        .andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true))
        .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
  }

  @Test
  void pkceConsentCsrfCodeExchangeIntrospectionAndRevoke() throws Exception {
    String code = authorize();
    var issued = token(code, VERIFIER, RESOURCE, 200);
    assertThat(issued.path("access_token").asText()).matches("tsk_oat_[A-Za-z0-9_-]{43}");
    assertThat(issued.path("refresh_token").asText()).matches("tsk_ort_[A-Za-z0-9_-]{43}");
    var access = introspect(issued.path("access_token").asText());
    assertThat(access.path("active").asBoolean()).isTrue();
    assertThat(access.path("sub").asText()).isEqualTo(ACTOR);
    assertThat(access.path("aud").get(0).asText()).isEqualTo(RESOURCE);
    assertThat(access.path("grant_id").asText()).isNotBlank();
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
                                    access.path("grant_id").asText(),
                                    "token_id",
                                    access.path("jti").asText()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(parent.path("active").asBoolean()).isTrue();
    mvc.perform(
            delete("/oauth/grants/" + access.path("grant_id").asText())
                .with(authentication(actor()))
                .with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isFalse();
  }

  @Test
  void invalidVerifierWrongResourceAndMissingResourceCannotExchange() throws Exception {
    String code = authorize();
    token(code, VERIFIER, "https://other.example/mcp", 400);
    mvc.perform(
            post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT)
                .param("code", code)
                .param("redirect_uri", REDIRECT)
                .param("code_verifier", VERIFIER))
        .andExpect(status().isBadRequest());
    token(code, "B".repeat(43), RESOURCE, 400);
  }

  @Test
  void noPlainPkceNoForeignRedirectNoMissingRead() throws Exception {
    for (var values :
        List.of(
            new String[] {"plain", REDIRECT, "read", RESOURCE},
            new String[] {"S256", "https://evil.example/callback", "read", RESOURCE},
            new String[] {"S256", REDIRECT, "write", RESOURCE},
            new String[] {"S256", REDIRECT, "read", "https://other.example/mcp"})) {
      var response =
          mvc.perform(
                  get("/oauth2/authorize")
                      .with(authentication(actor()))
                      .queryParam("response_type", "code")
                      .queryParam("client_id", CLIENT)
                      .queryParam("redirect_uri", values[1])
                      .queryParam("scope", values[2])
                      .queryParam("state", "client-state")
                      .queryParam("resource", values[3])
                      .queryParam("code_challenge", challenge())
                      .queryParam("code_challenge_method", values[0]))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isIn(302, 400);
      if (response.getStatus() == 302)
        assertThat(response.getRedirectedUrl())
            .contains("error=")
            .doesNotContain("code=")
            .startsWith(REDIRECT);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void rotatingRefreshReuseRevokesFamilyIncludingNewToken() throws Exception {
    var original = token(authorize(), VERIFIER, RESOURCE, 200);
    String refresh = original.path("refresh_token").asText();
    var next = refresh(refresh, 200);
    assertThat(next.path("refresh_token").asText()).isNotEqualTo(refresh);
    refresh(refresh, 400);
    assertThat(introspect(next.path("access_token").asText()).path("active").asBoolean()).isFalse();
    refresh(next.path("refresh_token").asText(), 400);
  }

  @Test
  void concurrentCodeRedemptionHasOneWinnerAndReplayRevokesIssuedFamily() throws Exception {
    String code = authorize();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(4);
      var start = new CountDownLatch(1);
      Callable<org.springframework.mock.web.MockHttpServletResponse> call = () -> {
        ready.countDown();
        start.await();
        return mvc.perform(post("/oauth2/token")
            .param("grant_type", "authorization_code").param("client_id", CLIENT)
            .param("code", code).param("redirect_uri", REDIRECT)
            .param("code_verifier", VERIFIER).param("resource", RESOURCE))
            .andReturn().getResponse();
      };
      var futures = new ArrayList<Future<org.springframework.mock.web.MockHttpServletResponse>>();
      for (int i = 0; i < 4; i++) futures.add(executor.submit(call));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var responses = new ArrayList<org.springframework.mock.web.MockHttpServletResponse>();
      for (var future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
      assertThat(responses.stream().map(r -> r.getStatus()).toList())
          .containsExactlyInAnyOrder(200, 400, 400, 400);
      var issued = mapper.readTree(responses.stream().filter(r -> r.getStatus() == 200)
          .findFirst().orElseThrow().getContentAsString());
      assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
          .isFalse();
      refresh(issued.path("refresh_token").asText(), 400);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void concurrentRefreshOneWinnerThenReplayRevokesFamily() throws Exception {
    String refresh = token(authorize(), VERIFIER, RESOURCE, 200).path("refresh_token").asText();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      Callable<Integer> call =
          () -> {
            start.await();
            return mvc.perform(
                    post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("client_id", CLIENT)
                        .param("refresh_token", refresh)
                        .param("resource", RESOURCE))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var first = executor.submit(call);
      var second = executor.submit(call);
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 400);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void serviceCredentialsAndGrantOwnershipFailClosed() throws Exception {
    var issued = token(authorize(), VERIFIER, RESOURCE, 200);
    var access = introspect(issued.path("access_token").asText());
    mvc.perform(post("/internal/grants/validate").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/internal/grants/validate")
                .with(httpBasic("auth-service", "wrong"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            delete("/oauth/grants/" + access.path("grant_id").asText())
                .with(
                    authentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                            UUID.randomUUID().toString(),
                            null,
                            List.of(new SimpleGrantedAuthority("USER")))))
                .with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/oauth/grants/" + access.path("grant_id").asText())
                .with(authentication(actor())))
        .andExpect(status().isForbidden());
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isTrue();
  }

  @Test
  void registrationRejectsUnapprovedRedirectCredentialsAndOversizedJson() throws Exception {
    for (String invalid :
        List.of(
            "{\"client_name\":\"Bad\",\"redirect_uris\":[\"https://evil.example/callback\"]}",
            "{\"client_name\":\"Bad\",\"redirect_uris\":[\""
                + REDIRECT
                + "\"],\"client_secret\":\"attacker\"}"))
      mvc.perform(post("/oauth/register").contentType("application/json").content(invalid))
          .andExpect(status().isBadRequest());
    mvc.perform(post("/oauth/register").contentType("application/json").content("x".repeat(65537)))
        .andExpect(status().isPayloadTooLarge());
    String foreign = "https://evil.example/client.json";
    assertThat(clients.findByClientId(foreign)).isNull();
    verify(fetcher, never()).fetch(URI.create(foreign));
  }

  @Test
  void refreshAbsoluteFamilyExpiryDoesNotExtendAndExpiredFamilyRejects() throws Exception {
    var original = token(authorize(), VERIFIER, RESOURCE, 200);
    var expires =
        jdbc.queryForObject(
            "SELECT family_expires_at FROM oauth_refresh_history WHERE token_hash=?",
            java.sql.Timestamp.class,
            OAuthGrantStore.hash(original.path("refresh_token").asText()));
    var next = refresh(original.path("refresh_token").asText(), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT family_expires_at FROM oauth_refresh_history WHERE token_hash=?",
                java.sql.Timestamp.class,
                OAuthGrantStore.hash(next.path("refresh_token").asText())))
        .isEqualTo(expires);
    jdbc.update("UPDATE oauth_refresh_history SET family_expires_at=now()-interval '1 second'");
    refresh(next.path("refresh_token").asText(), 400);
    assertThat(introspect(next.path("access_token").asText()).path("active").asBoolean()).isFalse();
  }

  @Test
  void downscopedRefreshStillRotatesOriginalOfflineFamily() throws Exception {
    var original = token(authorize(), VERIFIER, RESOURCE, 200);
    var next =
        mapper.readTree(
            mvc.perform(
                    post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("client_id", CLIENT)
                        .param("refresh_token", original.path("refresh_token").asText())
                        .param("resource", RESOURCE)
                        .param("scope", "read"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(next.path("refresh_token").asText()).matches("tsk_ort_[A-Za-z0-9_-]{43}");
    assertThat(introspect(next.path("access_token").asText()).path("scope").asText())
        .isEqualTo("read");
  }

  @Test
  void anotherClientCannotRevokeRefreshFamilyByReplayingItsToken() throws Exception {
    var original = token(authorize(), VERIFIER, RESOURCE, 200);
    var next = refresh(original.path("refresh_token").asText(), 200);
    var other =
        clients.registerDcr(
            mapper.readTree(
                "{\"client_name\":\"Other client\",\"redirect_uris\":[\""
                    + REDIRECT
                    + "\"],\"grant_types\":[\"authorization_code\",\"refresh_token\"]}"));
    mvc.perform(
            post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("client_id", other.getClientId())
                .param("refresh_token", original.path("refresh_token").asText())
                .param("resource", RESOURCE))
        .andExpect(status().isBadRequest());
    assertThat(introspect(next.path("access_token").asText()).path("active").asBoolean()).isTrue();
  }

  @Test
  void browserProtocolCorsDoesNotExposeAuthenticatedConsentOrGrantApi() throws Exception {
    mvc.perform(
            get("/.well-known/oauth-authorization-server")
                .header("Origin", "http://localhost:6274"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    mvc.perform(
            options("/oauth2/token")
                .header("Origin", "http://localhost:6274")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    mvc.perform(
            get("/oauth/grants")
                .with(authentication(actor()))
                .header("Origin", "https://evil.example"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(
            options("/internal/grants/validate")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void standardRevocationRemovesWholeFamilyAndChecksClientOwnership() throws Exception {
    var issued = token(authorize(), VERIFIER, RESOURCE, 200);
    var other =
        clients.registerDcr(
            mapper.readTree(
                "{\"client_name\":\"Other revoker\",\"redirect_uris\":[\"" + REDIRECT + "\"]}"));
    mvc.perform(
            post("/oauth2/revoke")
                .param("client_id", other.getClientId())
                .param("token", issued.path("access_token").asText()))
        .andExpect(status().isBadRequest());
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isTrue();
    mvc.perform(
            post("/oauth2/revoke")
                .param("client_id", CLIENT)
                .param("token", issued.path("access_token").asText()))
        .andExpect(status().isOk());
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isFalse();
    refresh(issued.path("refresh_token").asText(), 400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth2_authorization", Integer.class))
        .isZero();
  }

  @Test
  void codeOnlyPublicClientCanRevokeItsAccessToken() throws Exception {
    var client =
        clients.registerDcr(
            mapper.readTree(
                "{\"client_name\":\"Online client\",\"redirect_uris\":[\"" + REDIRECT + "\"]}"));
    String code = authorize(client.getClientId(), "read");
    var issued =
        mapper.readTree(
            mvc.perform(
                    post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", client.getClientId())
                        .param("code", code)
                        .param("redirect_uri", REDIRECT)
                        .param("code_verifier", VERIFIER)
                        .param("resource", RESOURCE))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(issued.has("refresh_token")).isFalse();
    mvc.perform(
            post("/oauth2/revoke")
                .param("client_id", client.getClientId())
                .param("token", issued.path("access_token").asText()))
        .andExpect(status().isOk());
    assertThat(introspect(issued.path("access_token").asText()).path("active").asBoolean())
        .isFalse();
  }

  @Test
  void connectedGrantsOmitAnExpiredCodeOnlyAuthorization() throws Exception {
    var issued = token(authorize(CLIENT, "read"), VERIFIER, RESOURCE, 200);
    assertThat(issued.has("refresh_token")).isFalse();
    String id =
        grants
            .findByToken(issued.path("access_token").asText(), OAuth2TokenType.ACCESS_TOKEN)
            .getId();
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].grant_id").value(id));
    jdbc.update(
        "UPDATE oauth2_authorization SET access_token_issued_at=now()-interval '20 minutes', access_token_expires_at=now()-interval '1 second' WHERE"
            + " id=?",
        id);
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void connectedGrantsKeepActiveOfflineRefreshButOmitFullyExpiredOrInvalidatedTokens()
      throws Exception {
    var issued = token(authorize(), VERIFIER, RESOURCE, 200);
    String id =
        grants
            .findByToken(issued.path("access_token").asText(), OAuth2TokenType.ACCESS_TOKEN)
            .getId();
    jdbc.update(
        "UPDATE oauth2_authorization SET access_token_issued_at=now()-interval '20 minutes', access_token_expires_at=now()-interval '1 second' WHERE"
            + " id=?",
        id);
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].grant_id").value(id));
    jdbc.update(
        "UPDATE oauth2_authorization SET refresh_token_issued_at=now()-interval '2 hours', refresh_token_expires_at=now()-interval '1 second' WHERE"
            + " id=?",
        id);
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    jdbc.update(
        "UPDATE oauth2_authorization SET refresh_token_expires_at=now()+interval '1 hour' WHERE"
            + " id=?",
        id);
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].grant_id").value(id));
    var authorization = grants.findById(id);
    grants.save(
        OAuth2Authorization.from(authorization)
            .invalidate(authorization.getRefreshToken().getToken())
            .build());
    mvc.perform(get("/oauth/grants").with(authentication(actor())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  private String authorize() throws Exception {
    return authorize(CLIENT, "read write offline_access");
  }

  private String authorize(String clientId, String scopes) throws Exception {
    MockHttpSession session = new MockHttpSession();
    var redirect =
        mvc.perform(
                get("/oauth2/authorize")
                    .session(session)
                    .with(authentication(actor()))
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId)
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
            UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("state"),
            StandardCharsets.UTF_8);
    var contract =
        mapper.readTree(
            mvc.perform(
                    get("/oauth/consent")
                        .session(session)
                        .with(authentication(actor()))
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(
            post("/oauth2/authorize")
                .session(session)
                .with(authentication(actor()))
                .param("client_id", clientId)
                .param("state", state)
                .param("scope", scopes.split(" ")))
        .andExpect(status().isForbidden());
    var completed =
        mvc.perform(
                post("/oauth2/authorize")
                    .session(session)
                    .with(authentication(actor()))
                    .param("client_id", clientId)
                    .param("state", state)
                    .param("scope", scopes.split(" "))
                    .param(
                        contract.path("csrf_parameter").asText(),
                        contract.path("csrf_token").asText()))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(completed).startsWith(REDIRECT).contains("iss=");
    String code =
        java.net.URLDecoder.decode(
            UriComponentsBuilder.fromUriString(completed).build().getQueryParams().getFirst("code"),
            StandardCharsets.UTF_8);
    assertThat(code).isNotBlank();
    return code;
  }

  private JsonNode token(String code, String verifier, String resource, int status)
      throws Exception {
    return mapper.readTree(
        mvc.perform(
                post("/oauth2/token")
                    .param("grant_type", "authorization_code")
                    .param("client_id", CLIENT)
                    .param("code", code)
                    .param("redirect_uri", REDIRECT)
                    .param("code_verifier", verifier)
                    .param("resource", resource))
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
                post("/oauth2/introspect")
                    .with(httpBasic("auth-service", SECRET))
                    .param("token", token))
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
