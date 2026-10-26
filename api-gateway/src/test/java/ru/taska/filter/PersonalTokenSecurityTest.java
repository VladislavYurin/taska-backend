package ru.taska.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayTokenKind;
import ru.taska.mapper.AuthMapper;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

class PersonalTokenSecurityTest {
  @Test
  void readPatReadsButCannotWriteAndDoesNotGainGlobalAdminOrSessionRights() {
    var factory = factory(AccessTokenKind.ACCESS_TOKEN_KIND_PAT, List.of("read"));
    StepVerifier.create(
            factory.buildContext(
                "request",
                exchange(HttpMethod.GET, "/api/v1/issues/search"),
                EndpointSecurity.PROTECTED))
        .assertNext(
            context ->
                assertThat(context.userContext().tokenKind()).isEqualTo(GatewayTokenKind.PAT))
        .verifyComplete();
    for (HttpMethod method :
        List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.DELETE)) {
      String path = method == HttpMethod.POST ? "/api/v1/projects"
          : method == HttpMethod.PATCH ? "/api/v1/issues/id"
          : "/api/v1/projects/p/issues/i/watchers/me";
      denied(
          factory,
          method,
          path,
          EndpointSecurity.PROTECTED,
          "INSUFFICIENT_SCOPE",
          "write");
    }
    for (String path : List.of("/api/v1/admin/users", "/api/v1/readonly/catalog")) {
      denied(
          factory,
          HttpMethod.GET,
          path,
          EndpointSecurity.GLOBAL_ADMIN_REQUIRED,
          "SESSION_REQUIRED",
          null);
      denied(factory, HttpMethod.GET, path, EndpointSecurity.PROTECTED, "SESSION_REQUIRED", null);
    }
    denied(
        factory,
        HttpMethod.GET,
        "/api/v1/users/me/access-tokens",
        EndpointSecurity.JWT_SESSION_REQUIRED,
        "SESSION_REQUIRED",
        null);
  }

  @Test
  void writePatChangesOrdinaryObjectsButCannotManageTokens() {
    var factory = factory(AccessTokenKind.ACCESS_TOKEN_KIND_PAT, List.of("read", "write"));
    StepVerifier.create(
            factory.buildContext(
                "request",
                exchange(HttpMethod.POST, "/api/v1/projects"),
                EndpointSecurity.PROTECTED))
        .expectNextCount(1)
        .verifyComplete();
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE)) {
      denied(
          factory,
          method,
          "/api/v1/users/me/access-tokens",
          EndpointSecurity.JWT_SESSION_REQUIRED,
          "SESSION_REQUIRED",
          null);
    }
  }

  @Test
  void jwtAndLegacyJwtKeepAccessIncludingLockedSession() {
    for (AccessTokenKind kind :
        List.of(
            AccessTokenKind.ACCESS_TOKEN_KIND_JWT, AccessTokenKind.ACCESS_TOKEN_KIND_UNSPECIFIED)) {
      var factory = factory(kind, List.of());
      StepVerifier.create(
              factory.buildContext(
                  "request",
                  exchange(HttpMethod.POST, "/api/v1/projects"),
                  EndpointSecurity.PROTECTED))
          .expectNextCount(1)
          .verifyComplete();
      StepVerifier.create(
              factory.buildContext(
                  "request",
                  exchange(HttpMethod.GET, "/api/v1/readonly/catalog"),
                  EndpointSecurity.GLOBAL_ADMIN_REQUIRED))
          .expectNextCount(1)
          .verifyComplete();
    }
    var mapper = new ContextMapper(new AuthMapper());
    assertThat(
            mapper
                .mapToGatewayUserContext(
                    UserContext.newBuilder()
                        .setStatus(UserStatus.USER_STATUS_LOCKED)
                        .setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_JWT)
                        .build())
                .status()
                .name())
        .isEqualTo("LOCKED");
  }

  @Test
  void explicitContractAllowsReadPostWithoutMethodGuessing() {
    var policy =
        new EndpointAccessPolicy(
            Map.of(
                "paths",
                Map.of(
                    "/api/v1/issues/search",
                    Map.of(
                        "post",
                        Map.of(
                            "security",
                            List.of(Map.of("bearerAuth", List.of())),
                            "x-pat-scope",
                            "read")))));
    assertThat(policy.scope(exchange(HttpMethod.POST, "/api/v1/issues/search").getRequest()))
        .isEqualTo("read");
    assertThat(
            EndpointAccessPolicy.load()
                .scope(
                    exchange(HttpMethod.GET, "/api/v1/users/me/access-tokens;ignored=value")
                        .getRequest()))
        .isEqualTo("session");
  }

  @Test
  void undocumentedEndpointsRejectPatButLegacySelfReadHasExplicitPolicy() {
    var factory = factory(AccessTokenKind.ACCESS_TOKEN_KIND_PAT, List.of("read", "write"));
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE)) {
      denied(factory, method, "/api/v1/future-endpoint", EndpointSecurity.PROTECTED, "SESSION_REQUIRED", null);
    }
    StepVerifier.create(factory.buildContext("request", exchange(HttpMethod.GET, "/api/gateway/me"),
        EndpointSecurity.PROTECTED)).expectNextCount(1).verifyComplete();
    denied(factory, HttpMethod.POST, "/api/gateway/me", EndpointSecurity.PROTECTED, "SESSION_REQUIRED", null);
  }

  private static GatewayContextFactory factory(AccessTokenKind kind, List<String> scopes) {
    var auth = mock(GrpcAuthServiceClient.class);
    var user =
        UserContext.newBuilder()
            .setUserId("user-id")
            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
            .setStatus(UserStatus.USER_STATUS_ACTIVE)
            .setTokenKind(kind)
            .addAllScopes(scopes)
            .setTokenId("token-id")
            .build();
    when(auth.validateAccessToken(anyString(), anyString(), anyString()))
        .thenReturn(
            Mono.just(ValidateAccessTokenResponse.newBuilder().setUserContext(user).build()));
    var factory =
        new GatewayContextFactory(
            new BearerTokenExtractor(), auth, new ContextMapper(new AuthMapper()));
    ReflectionTestUtils.setField(factory, "nodeId", "gateway-test");
    return factory;
  }

  private static void denied(
      GatewayContextFactory factory,
      HttpMethod method,
      String path,
      EndpointSecurity security,
      String reason,
      String required) {
    StepVerifier.create(factory.buildContext("request", exchange(method, path), security))
        .expectErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(StatusRuntimeException.class);
              var failure = (StatusRuntimeException) error;
              assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
              assertThat(GrpcErrorDetails.decode(failure.getTrailers()))
                  .containsEntry("reason", reason);
              if (required != null)
                assertThat(GrpcErrorDetails.decode(failure.getTrailers()))
                    .containsEntry("requiredScope", required);
            })
        .verify();
  }

  private static MockServerWebExchange exchange(HttpMethod method, String path) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.method(method, path).header("Authorization", "Bearer value"));
  }
}
