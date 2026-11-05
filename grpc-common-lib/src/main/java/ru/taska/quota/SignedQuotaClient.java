package ru.taska.quota;

import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.*;
import java.util.function.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/**
 * Shared transport mechanics only; every method still requires current issuer/purpose authority.
 */
public final class SignedQuotaClient {
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String issuer, audience;

  public SignedQuotaClient(
      RpcContextSecurity security, Clock clock, String issuer, String audience) {
    this.security = Objects.requireNonNull(security);
    this.clock = Objects.requireNonNull(clock);
    this.issuer = Objects.requireNonNull(issuer);
    this.audience = Objects.requireNonNull(audience);
  }

  public <Q extends Message, R> Mono<R> call(
      UUID tenant,
      String rpc,
      String action,
      Function<RequestContext, Q> request,
      BiFunction<Q, ClientInterceptor, Mono<R>> send) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent -> {
              if (!tenant.toString().equals(parent.tenantId()))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              var now = clock.instant();
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(
                          RpcContextBridge.details(parent).getContext().toBuilder()
                              .setContextVersion(1)
                              .setTenantId(tenant.toString())
                              .setIssuerService(issuer)
                              .setAudienceService(audience)
                              .setAction(action)
                              .setIssuedAt(now.toString())
                              .setExpiresAt(now.plusSeconds(30).toString())
                              .setAssertionId(UUID.randomUUID().toString()))
                      .build();
              Q wire = request.apply(context);
              var policy = new RpcContextSecurity.Policy(true, false);
              return security
                  .authorize(context, rpc, policy)
                  .flatMap(v -> security.sign(v, wire, rpc, policy))
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return send.apply(wire, MetadataUtils.newAttachHeadersInterceptor(headers));
                      });
            });
  }
}
