package ru.taska.oauth;

import static org.assertj.core.api.Assertions.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.*;

class IdentityErrorMappingTest {
  private Server server;
  private ManagedChannel channel;
  private TaskaIdentityClient client;
  private final AtomicReference<String> requestId = new AtomicReference<>();

  @BeforeEach void start() throws Exception {
    server = ServerBuilder.forPort(0).addService(new OAuthIdentityServiceGrpc.OAuthIdentityServiceImplBase() {
      @Override public void authenticateCredentials(LoginRequest request, StreamObserver<ValidateAccessTokenResponse> response) {
        requestId.set(request.getHeader().getRequestId());
        String email = request.getBody().getEmail();
        if (email.equals("locked")) response.onError(Status.PERMISSION_DENIED.asRuntimeException(
            GrpcErrorDetails.encode(Map.of("reason", "ACCOUNT_LOCKED", "lockedUntil", "2099-01-01T00:00:00Z"))));
        else if (Set.of("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL").contains(email))
          response.onError(Status.fromCode(Status.Code.valueOf(email)).asRuntimeException());
        else if (email.equals("invalid")) response.onError(Status.UNAUTHENTICATED.asRuntimeException());
        else {
          response.onNext(ValidateAccessTokenResponse.newBuilder().setUserContext(UserContext.newBuilder()
              .setUserId("d05c5390-7ad0-4acb-b614-307d849f974c").setEmail("actor@example.invalid")
              .setStatus(UserStatus.USER_STATUS_ACTIVE)).build()); response.onCompleted();
        }
      }
    }).build().start();
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    var properties = new OAuthServerProperties(URI.create("https://oauth.example.invalid"), URI.create("https://mcp.example.invalid/mcp"),
        List.of(), List.of(), false, 1000, Duration.ofMinutes(5), "auth-service", "a".repeat(40),
        new OAuthServerProperties.Auth("127.0.0.1", server.getPort(), Duration.ofSeconds(2), "s".repeat(40)));
    client = new TaskaIdentityClient(channel, properties);
  }
  @AfterEach void stop() { channel.shutdownNow(); server.shutdownNow(); }

  @Test void lockDeadlineAndInvalidCredentialsRemainDistinct() {
    assertThatThrownBy(() -> client.authenticate("locked", "secret"))
        .isInstanceOfSatisfying(OAuthAccountLockedException.class, failure ->
            assertThat(failure.until()).isEqualTo(java.time.Instant.parse("2099-01-01T00:00:00Z")));
    assertThatThrownBy(() -> client.authenticate("invalid", "secret")).isInstanceOf(BadCredentialsException.class);
  }
  @ParameterizedTest @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL"})
  void infrastructureErrorsAreNotReportedAsWrongPassword(String status) {
    assertThatThrownBy(() -> client.authenticate(status, "secret")).isInstanceOf(AuthenticationServiceException.class);
  }
  @Test void responseAndGrpcShareTheValidatedRequestId() throws Exception {
    var request = new MockHttpServletRequest(); request.addHeader("X-Request-Id", "tas445.correlation");
    var response = new MockHttpServletResponse();
    new OAuthRequestCorrelation().doFilter(request, response, (req, res) -> client.authenticate("valid", "secret"));
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("tas445.correlation");
    assertThat(requestId.get()).isEqualTo("tas445.correlation");
  }
}
