package ru.taska.security.oauth;

import io.grpc.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;
import security.OAuthServiceCredentials;

@Component
@GlobalServerInterceptor
public class OAuthServiceIdentityInterceptor implements ServerInterceptor {
  private final OAuthBridgeProperties properties;

  public OAuthServiceIdentityInterceptor(OAuthBridgeProperties properties) {
    this.properties = properties;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = call.getMethodDescriptor().getFullMethodName();
    String prefix = "ru.taska.auth.oauth.v1.OAuthIdentityService/";
    if (!method.startsWith(prefix)) return next.startCall(call, headers);
    String expected =
        switch (method.substring(prefix.length())) {
          case "AuthenticateCredentials", "ValidateSubject", "ClaimSignIn", "ListSignInProviders" ->
              properties.identityServiceCredential();
          case "ExchangeToken" -> properties.mcpExchangeCredential();
          default -> "";
        };
    String supplied = headers.get(OAuthServiceCredentials.KEY);
    if (!properties.enabled()
        || expected == null
        || expected.length() < 32
        || supplied == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
      call.close(
          Status.UNAUTHENTICATED.withDescription("OAuth service identity required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return next.startCall(call, headers);
  }
}
