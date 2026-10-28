package ru.taska.oauth;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mapper.GrpcErrorDetails;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.*;
import security.OAuthServiceCredentials;

/** Private service-identity channel: a social grant establishes a browser session, never API tokens. */
@Component
public class SocialIdentityClient {
  private static final Set<String> REASONS = Set.of("STATE_INVALID", "SIGNUP_REQUIRED", "ACCOUNT_BLOCKED",
      "ACCOUNT_LOCKED", "EMAIL_NOT_VERIFIED", "PROVIDER_DISABLED", "PROVIDER_UNAVAILABLE", "PROVIDER_CANCELLED");
  private final OAuthIdentityServiceGrpc.OAuthIdentityServiceBlockingStub stub;
  private final OAuthServerProperties policy;

  SocialIdentityClient(ManagedChannel channel, OAuthServerProperties policy) {
    this.policy = policy;
    stub = OAuthIdentityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(OAuthServiceCredentials.interceptor(policy.auth().serviceCredential()));
  }

  public List<String> providers() {
    try {
      return stub.withDeadlineAfter(policy.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
          .listSignInProviders(Empty.getDefaultInstance()).getProvidersList().stream()
          .filter(Set.of("GOOGLE", "GITHUB", "APPLE")::contains).distinct().toList();
    } catch (StatusRuntimeException failure) {
      // Password login remains available when the social catalog is temporarily unavailable.
      return List.of();
    }
  }

  public UserContext claim(String code, String nonce) {
    try {
      var subject = stub.withDeadlineAfter(policy.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
          .claimSignIn(ClaimOAuthSignInRequest.newBuilder()
              .setHeader(Header.newBuilder().setNodeId("oauth-server").setRequestId(OAuthRequestCorrelation.current()))
              .setCode(code).setNonce(nonce).build()).getUserContext();
      if (subject.getStatus() != UserStatus.USER_STATUS_ACTIVE
          || !subject.getUserId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        throw new Failure("STATE_INVALID");
      return subject;
    } catch (StatusRuntimeException failure) {
      String reason = GrpcErrorDetails.decode(failure.getTrailers()).get("reason");
      throw new Failure(REASONS.contains(reason == null ? "" : reason) ? reason : "SERVICE_UNAVAILABLE");
    }
  }

  static final class Failure extends RuntimeException {
    final String reason;
    Failure(String reason) { super("Social sign-in failed"); this.reason = reason; }
  }
}
