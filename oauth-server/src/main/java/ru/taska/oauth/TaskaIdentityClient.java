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

  /** TAS-498: consume an approval for this pending authorization; one success per grant. */
  public TenantOAuthGrantBinding claimTenantGrant(
      String grantId, String subject, String clientId, String authorizationRequestId) {
    return tenantGrant(
        () ->
            stub.withDeadlineAfter(properties.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
                .claimTenantOAuthGrant(
                    ClaimTenantOAuthGrantRequest.newBuilder()
                        .setHeader(header())
                        .setGrantId(grantId)
                        .setUserId(subject)
                        .setClientId(clientId)
                        .setAuthorizationRequestId(authorizationRequestId)
                        .build()),
        grantId);
  }

  /** TAS-498: the bound grant is still live at the generation recorded on the authorization. */
  public TenantOAuthGrantBinding validateTenantGrant(
      String grantId, String subject, String clientId, long version) {
    return tenantGrant(
        () ->
            stub.withDeadlineAfter(properties.auth().timeout().toMillis(), TimeUnit.MILLISECONDS)
                .validateTenantOAuthGrant(
                    ValidateTenantOAuthGrantRequest.newBuilder()
                        .setHeader(header())
                        .setGrantId(grantId)
                        .setUserId(subject)
                        .setClientId(clientId)
                        .setGrantGeneration(Long.toString(version))
                        .build()),
        grantId);
  }

  private static TenantOAuthGrantBinding tenantGrant(
      java.util.function.Supplier<TenantOAuthGrantBinding> call, String grantId) {
    try {
      var binding = call.get();
      if (!grantId.equals(binding.getGrantId()) || binding.getTenantId().isBlank())
        throw new TenantGrantRejectedException();
      return binding;
    } catch (StatusRuntimeException failure) {
      if (java.util.Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL)
          .contains(failure.getStatus().getCode())) throw new AuthenticationServiceException("Identity service is unavailable");
      throw new TenantGrantRejectedException();
    }
  }

  /** A definitive refusal: unknown, foreign, consumed, revoked or changed grant. */
  public static final class TenantGrantRejectedException extends RuntimeException {
    TenantGrantRejectedException() {
      super("Tenant OAuth grant rejected", null, false, false);
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
