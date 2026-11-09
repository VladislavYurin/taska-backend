package ru.taska.security.gateway;

import io.grpc.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.api.b2b.v1.GatewayCredentialServiceGrpc;
import ru.taska.repository.UserRepository;
import ru.taska.security.pat.TenantPersonalTokenStore;
import ru.taska.security.session.WebSessionStore;
import ru.taska.security.tenant.TenantServerInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
public class GatewayCredentialConfiguration {
  @Bean
  GatewayCredentialResolver gatewayCredentialResolver(
      DatabaseClient db,
      UserRepository users,
      WebSessionStore.MembershipAuthority membership,
      ObjectProvider<TenantPersonalTokenStore> tokens,
      @Value("${taska.b2b.sessions.ed25519-public-key:}") String key,
      @Value("${taska.b2b.sessions.ed25519-kid:}") String kid)
      throws GeneralSecurityException {
    if (kid.isBlank())
      throw new IllegalStateException("B2B credential verification key id is required");
    var publicKey =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
    return new GatewayCredentialResolver(
        db, users, membership, tokens.getIfAvailable(), publicKey, kid, Clock.systemUTC());
  }

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor credentialBootstrapPeer(
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    return new ServerInterceptor() {
      @Override
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        if (!GatewayCredentialServiceGrpc.getResolveGatewayCredentialMethod()
            .getFullMethodName()
            .equals(call.getMethodDescriptor().getFullMethodName()))
          return next.startCall(call, headers);
        try {
          if (!"api-gateway"
              .equals(TenantServerInterceptor.peerIdentity(call.getAttributes(), environment)))
            throw Status.UNAUTHENTICATED.asRuntimeException();
        } catch (Exception e) {
          call.close(Status.UNAUTHENTICATED, new Metadata());
          return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
      }
    };
  }
}
