package ru.taska.oauth;

import io.grpc.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import mapper.GrpcErrorDetails;
import java.time.Instant;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.*;
import security.OAuthServiceCredentials;

/** The authorization server never reads password or user tables. */
@Component
public class TaskaIdentityClient {
  private final OAuthIdentityServiceGrpc.OAuthIdentityServiceBlockingStub stub;
  private final OAuthServerProperties properties;

  public TaskaIdentityClient(ManagedChannel channel, OAuthServerProperties properties) {
    this.properties = properties;
    stub =
        OAuthIdentityServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                OAuthServiceCredentials.interceptor(properties.auth().serviceCredential()));
  }

  public UserContext authenticate(String email, String password) {
    try {
      var user =
          stub.withDeadlineAfter(properties.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
              .authenticateCredentials(
                  LoginRequest.newBuilder()
                      .setHeader(header())
                      .setBody(LoginRequestBody.newBuilder().setEmail(email).setPassword(password))
                      .build())
              .getUserContext();
      if (user.getStatus() != UserStatus.USER_STATUS_ACTIVE || user.getUserId().isBlank())
        throw new BadCredentialsException("Authentication failed");
      return user;
    } catch (StatusRuntimeException failure) {
      var details = GrpcErrorDetails.decode(failure.getTrailers());
      if (failure.getStatus().getCode() == Status.Code.PERMISSION_DENIED && (details.containsKey("lockedUntil") || "ACCOUNT_LOCKED".equals(details.get("reason")))) {
        Instant until = null;
        try { until = Instant.parse(details.get("lockedUntil")); } catch (RuntimeException ignored) { }
        throw new OAuthAccountLockedException(until);
      }
      if (java.util.Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL)
          .contains(failure.getStatus().getCode())) throw new AuthenticationServiceException("Identity service is unavailable");
      throw new BadCredentialsException("Authentication failed");
    }
  }

  public UserContext validate(String subject) {
    try {
      var user =
          stub.withDeadlineAfter(properties.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
              .validateSubject(
                  ValidateOAuthSubjectRequest.newBuilder()
                      .setHeader(header())
                      .setSubject(subject)
                      .build())
              .getUserContext();
      if (!subject.equals(user.getUserId()) || user.getStatus() != UserStatus.USER_STATUS_ACTIVE)
        throw new BadCredentialsException("Authentication failed");
      return user;
    } catch (StatusRuntimeException failure) {
      if (java.util.Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL)
          .contains(failure.getStatus().getCode())) throw new AuthenticationServiceException("Identity service is unavailable");
      throw new BadCredentialsException("Authentication failed");
    }
  }

  private Header header() {
    return Header.newBuilder()
        .setNodeId("oauth-server")
        .setRequestId(OAuthRequestCorrelation.current())
        .build();
  }

  @Configuration(proxyBeanMethods = false)
  static class Transport {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel identityChannel(OAuthServerProperties properties) {
      return ManagedChannelBuilder.forAddress(properties.auth().host(), properties.auth().port())
          .usePlaintext()
          .build();
    }
  }
}
