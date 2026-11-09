package ru.taska.security.tenant;

import com.google.protobuf.*;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Message-bound Ed25519 assertions. Providers must enforce hard trust TTL and fail closed. */
public final class RpcContextSecurity {
  public record Policy(boolean tenantRequired, boolean mutation) {}

  public record SigningKey(String kid, PrivateKey key) {}

  public interface Keys {
    SigningKey signingKey(String issuer);

    PublicKey verificationKey(String issuer, String kid);
  }

  /** Must atomically consume across every recipient replica until expiry + allowed skew. */
  public interface ReplayGuard {
    Mono<Boolean> consume(String issuer, String jti, Instant retainUntil);
  }

  /**
   * Checks current account/grant/membership and allowlisted caller -> RPC/action edge. Bootstrap
   * adapters must bind claims to the already authenticated original credential. Merely finding an
   * active account is never sufficient authorization.
   */
  public interface Authority {
    Mono<Boolean> allowed(RequestContext context, String rpc, Policy policy);
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
  private final String service;
  private final Clock clock;
  private final Keys keys;
  private final ReplayGuard replay;
  private final Authority authority;

  public RpcContextSecurity(
      String service, Clock clock, Keys keys, ReplayGuard replay, Authority authority) {
    this.service = Objects.requireNonNull(service);
    this.clock = Objects.requireNonNull(clock);
    this.keys = Objects.requireNonNull(keys);
    this.replay = Objects.requireNonNull(replay);
    this.authority = Objects.requireNonNull(authority);
  }

  public static RequestContext context(Message message) {
    var f = message.getDescriptorForType().findFieldByName("context");
    if (f == null
        || f.isRepeated()
        || f.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE
        || !f.getMessageType().getFullName().equals(RequestContext.getDescriptor().getFullName())
        || !message.hasField(f)) throw unauthenticated();
    try {
      return RequestContext.parseFrom(((Message) message.getField(f)).toByteArray());
    } catch (Exception e) {
      throw unauthenticated();
    }
  }

  private static byte[] deterministic(Message message) {
    try {
      byte[] data = new byte[message.getSerializedSize()];
      var out = CodedOutputStream.newInstance(data);
      out.useDeterministicSerialization();
      message.writeTo(out);
      out.checkNoSpaceLeft();
      return data;
    } catch (Exception e) {
      throw unauthenticated();
    }
  }

  private static String digest(Message message) {
    try {
      return ENCODE.encodeToString(
          MessageDigest.getInstance("SHA-256").digest(deterministic(message)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void known(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) throw unauthenticated();
    for (var entry : message.getAllFields().entrySet()) {
      var f = entry.getKey();
      var values = f.isRepeated() ? (List<?>) entry.getValue() : List.of(entry.getValue());
      for (Object value : values) {
        if (value instanceof Message child) known(child);
        if (value instanceof Descriptors.EnumValueDescriptor e && e.getIndex() < 0)
          throw unauthenticated();
      }
    }
  }

  private void validate(RequestContext request, Policy policy) {
    known(request);
    var c = request.getContext();
    try {
      UUID.fromString(request.getHeader().getRequestId());
      UUID.fromString(c.getAssertionId());
      if (c.hasActorUserId()) UUID.fromString(c.getActorUserId());
      if (c.hasTenantId()) UUID.fromString(c.getTenantId());
      // exp-iat is the credential TTL; receiver tolerance is a separate bounded clock skew.
      Instant issued = Instant.parse(c.getIssuedAt()),
          expires = Instant.parse(c.getExpiresAt()),
          now = clock.instant();
      if (!request.hasHeader()
          || !request.hasContext()
          || c.getContextVersion() != 1
          || c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_UNSPECIFIED
          || c.getIssuerService().isBlank()
          || c.getAudienceService().isBlank()
          || c.getAction().isBlank()
          || c.getActorGeneration().isBlank()
          || (!c.hasActorUserId()
              && c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE)
          || (policy.tenantRequired() && !c.hasTenantId())
          || !expires.isAfter(issued)
          || java.time.Duration.between(issued, expires).compareTo(java.time.Duration.ofSeconds(30))
              > 0
          || issued.isAfter(now.plusSeconds(5))
          || !now.isBefore(expires.plusSeconds(5))) throw unauthenticated();
    } catch (RuntimeException e) {
      throw unauthenticated();
    }
  }

  private VerifiedTenantContext verified(RequestContext r) {
    var c = r.getContext();
    return new VerifiedTenantContext(
        c.hasActorUserId() ? c.getActorUserId() : null,
        c.hasTenantId() ? c.getTenantId() : null,
        r.getHeader().getRequestId(),
        c.getAction(),
        Set.copyOf(c.getScopesList()),
        r.toByteArray());
  }

  /**
   * Bootstrap hook: caller must be an authenticated gateway/service adapter, never raw wire input.
   */
  public Mono<VerifiedTenantContext> authorize(RequestContext r, String rpc, Policy policy) {
    return Mono.defer(
            () -> {
              validate(r, policy);
              return Mono.defer(() -> authority.allowed(r, rpc, policy))
                  .switchIfEmpty(Mono.error(Status.UNAVAILABLE.asRuntimeException()))
                  .onErrorMap(
                      e ->
                          Status.UNAVAILABLE
                              .withDescription("RPC authority unavailable")
                              .withCause(e)
                              .asRuntimeException())
                  // This operator precedes the explicit denial: false must remain
                  // PERMISSION_DENIED.
                  .flatMap(
                      ok ->
                          ok
                              ? Mono.just(verified(r))
                              : Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  /** Construct the next hop from verified provenance, with a new attempt identity. */
  public RequestContext delegate(
      VerifiedTenantContext parent, String audience, String action, Set<String> scopes) {
    try {
      var original = RequestContext.parseFrom(parent.wire());
      if (!parent.scopes().containsAll(scopes)) throw Status.PERMISSION_DENIED.asRuntimeException();
      var now = clock.instant();
      var c =
          original.getContext().toBuilder()
              .clearScopes()
              .addAllScopes(scopes)
              .setAction(action)
              .setIssuerService(service)
              .setAudienceService(audience)
              .setIssuedAt(now.toString())
              .setExpiresAt(now.plusSeconds(30).toString())
              .setAssertionId(UUID.randomUUID().toString());
      return original.toBuilder().setContext(c).build();
    } catch (InvalidProtocolBufferException e) {
      throw unauthenticated();
    }
  }

  private static String encode(Object value) {
    return ENCODE.encodeToString(JSON.writeValueAsBytes(value));
  }

  private static boolean samePrincipal(TrustedTenantContext a, TrustedTenantContext b) {
    return a.toBuilder()
            .clearScopes()
            .clearAction()
            .clearIssuerService()
            .clearAudienceService()
            .clearIssuedAt()
            .clearExpiresAt()
            .clearAssertionId()
            .build()
            .equals(
                b.toBuilder()
                    .clearScopes()
                    .clearAction()
                    .clearIssuerService()
                    .clearAudienceService()
                    .clearIssuedAt()
                    .clearExpiresAt()
                    .clearAssertionId()
                    .build())
        && new HashSet<>(a.getScopesList()).containsAll(b.getScopesList());
  }

  /** Each transport attempt gets a fresh jti while preserving the business payload. */
  public Message prepare(VerifiedTenantContext parent, Message request) {
    var requested = context(request);
    try {
      var original = RequestContext.parseFrom(parent.wire());
      if (!samePrincipal(original.getContext(), requested.getContext())
          || !original.getHeader().getRequestId().equals(requested.getHeader().getRequestId()))
        throw Status.PERMISSION_DENIED.asRuntimeException();
      var next =
          delegate(
              parent,
              requested.getContext().getAudienceService(),
              requested.getContext().getAction(),
              Set.copyOf(requested.getContext().getScopesList()));
      return request.toBuilder()
          .setField(request.getDescriptorForType().findFieldByName("context"), next)
          .build();
    } catch (InvalidProtocolBufferException e) {
      throw unauthenticated();
    }
  }

  public Mono<String> sign(
      VerifiedTenantContext parent, Message request, String rpc, Policy policy) {
    return Mono.defer(
            () -> {
              RequestContext r = context(request);
              var c = r.getContext();
              RequestContext original;
              try {
                original = RequestContext.parseFrom(parent.wire());
              } catch (Exception e) {
                throw unauthenticated();
              }
              if (!samePrincipal(original.getContext(), c)
                  || !original.getHeader().getRequestId().equals(r.getHeader().getRequestId())
                  || !service.equals(c.getIssuerService()))
                throw Status.PERMISSION_DENIED.asRuntimeException();
              RpcMessageClaims.validate(request, rpc, r);
              return authorize(r, rpc, policy)
                  .map(
                      ignored -> {
                        try {
                          var k = keys.signingKey(service);
                          String header =
                              encode(Map.of("alg", "EdDSA", "kid", k.kid(), "typ", "JWT"));
                          String body =
                              encode(
                                  Map.of(
                                      "iss",
                                      service,
                                      "aud",
                                      c.getAudienceService(),
                                      "iat",
                                      Instant.parse(c.getIssuedAt()).getEpochSecond(),
                                      "exp",
                                      Instant.parse(c.getExpiresAt()).getEpochSecond(),
                                      "jti",
                                      c.getAssertionId(),
                                      "request_id",
                                      r.getHeader().getRequestId(),
                                      "rpc",
                                      rpc,
                                      "context_sha256",
                                      digest(r),
                                      "request_sha256",
                                      digest(request)));
                          String input = header + "." + body;
                          var signer = Signature.getInstance("Ed25519");
                          signer.initSign(k.key());
                          signer.update(input.getBytes(StandardCharsets.US_ASCII));
                          return input + "." + ENCODE.encodeToString(signer.sign());
                        } catch (Exception e) {
                          throw Status.UNAVAILABLE
                              .withDescription("RPC signing unavailable")
                              .withCause(e)
                              .asRuntimeException();
                        }
                      });
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  public Mono<VerifiedTenantContext> verify(
      Message request, String assertion, String rpc, String peer, Policy policy) {
    return Mono.defer(
            () -> {
              var r = context(request);
              validate(r, policy);
              var c = r.getContext();
              try {
                if (assertion == null || assertion.length() > 16384) throw unauthenticated();
                String[] parts = assertion.split("\\.", -1);
                if (parts.length != 3) throw unauthenticated();
                JsonNode h = JSON.readTree(DECODE.decode(parts[0])),
                    b = JSON.readTree(DECODE.decode(parts[1]));
                if (!"EdDSA".equals(h.path("alg").asText())
                    || !"JWT".equals(h.path("typ").asText())
                    || h.size() != 3
                    || h.path("kid").asText().isBlank()
                    || b.size() != 9
                    || !service.equals(c.getAudienceService())
                    || !c.getIssuerService().equals(peer)
                    || !c.getIssuerService().equals(b.path("iss").asText())
                    || !service.equals(b.path("aud").asText())
                    || !rpc.equals(b.path("rpc").asText())
                    || !c.getAssertionId().equals(b.path("jti").asText())
                    || !r.getHeader().getRequestId().equals(b.path("request_id").asText())
                    || Instant.parse(c.getIssuedAt()).getEpochSecond() != b.path("iat").asLong()
                    || Instant.parse(c.getExpiresAt()).getEpochSecond() != b.path("exp").asLong()
                    || !digest(r).equals(b.path("context_sha256").asText())
                    || !digest(request).equals(b.path("request_sha256").asText()))
                  throw unauthenticated();
                PublicKey key;
                try {
                  key = keys.verificationKey(peer, h.path("kid").asText());
                } catch (Exception e) {
                  throw Status.UNAVAILABLE.asRuntimeException();
                }
                if (key == null) throw unauthenticated();
                var verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (!verifier.verify(DECODE.decode(parts[2]))) throw unauthenticated();
              } catch (io.grpc.StatusRuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw unauthenticated();
              }
              RpcMessageClaims.validate(request, rpc, r);
              return authorize(r, rpc, policy)
                  .flatMap(
                      v -> {
                        if (!policy.mutation()) return Mono.just(v);
                        return Mono.defer(
                                () ->
                                    replay.consume(
                                        peer,
                                        c.getAssertionId(),
                                        Instant.parse(c.getExpiresAt()).plusSeconds(5)))
                            .switchIfEmpty(Mono.error(Status.UNAVAILABLE.asRuntimeException()))
                            .onErrorMap(e -> Status.UNAVAILABLE.asRuntimeException())
                            .flatMap(ok -> ok ? Mono.just(v) : Mono.error(unauthenticated()));
                      });
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  static io.grpc.StatusRuntimeException unauthenticated() {
    return Status.UNAUTHENTICATED.asRuntimeException();
  }
}
