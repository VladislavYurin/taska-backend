package ru.taska.security.oauth;

import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.*;
import ru.taska.exception.*;
import ru.taska.service.AuthService;
import ru.taska.transport.grpc.AuthGrpcService;

class OAuthCapabilityBoundaryTest {
  @Test
  void oldClientsAndGatewayCannotValidateOriginalOpaqueButGatewayCanValidateDelegation() {
    var auth = mock(AuthService.class);
    var oauth = mock(OAuthTokenService.class);
    var service = new AuthGrpcService(auth, oauth);
    String original = "tsk_oat_" + "A".repeat(43);
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"typ\":\"taska-delegation+jwt\"}".getBytes(StandardCharsets.UTF_8));
    String delegated = header + ".payload.signature";
    for (String token : new String[] {original, delegated}) {
      var request = request(token, false);
      StepVerifier.create(service.validateAccessToken(Mono.just(request)))
          .expectErrorMatches(
              error ->
                  error instanceof DomainException domain
                      && domain.getStatus() == DomainStatus.UNAUTHENTICATED)
          .verify();
    }
    StepVerifier.create(service.validateAccessToken(Mono.just(request(original, true))))
        .expectErrorMatches(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.UNAUTHENTICATED)
        .verify();
    verifyNoInteractions(auth, oauth);
    when(oauth.validateDelegation(delegated))
        .thenReturn(
            Mono.just(
                UserContext.newBuilder()
                    .setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_DELEGATION)
                    .build()));
    StepVerifier.create(service.validateAccessToken(Mono.just(request(delegated, true))))
        .expectNextCount(1)
        .verifyComplete();
    verify(oauth).validateDelegation(delegated);
    verifyNoInteractions(auth);
  }

  private ValidateAccessTokenRequest request(String token, boolean gateway) {
    return ValidateAccessTokenRequest.newBuilder()
        .setHeader(Header.newBuilder().setNodeId("untrusted-node").setRequestId("request"))
        .setBody(
            ValidateAccessTokenRequestBody.newBuilder()
                .setAccessToken(token)
                .setAllowPersonalToken(true)
                .setAllowDelegatedToken(gateway))
        .build();
  }
}
