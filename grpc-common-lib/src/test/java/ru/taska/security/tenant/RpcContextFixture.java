package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import io.grpc.Status;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;

abstract class RpcContextFixture {
  static final String RPC = "test.Echo/Call",
      ACTOR = UUID.randomUUID().toString(),
      TENANT = UUID.randomUUID().toString();
  static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  static final RpcContextSecurity.Policy POLICY = new RpcContextSecurity.Policy(true, true);
  static Descriptors.Descriptor descriptor;
  RpcContextSecurity sender, recipient;
  Set<String> consumed;
  KeyPair pair;
  boolean allowed = true;

  @BeforeAll
  static void descriptor() throws Exception {
    var field =
        DescriptorProtos.FieldDescriptorProto.newBuilder()
            .setName("context")
            .setNumber(1)
            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(".ru.taska.b2b.v1.RequestContext");
    var payload =
        DescriptorProtos.FieldDescriptorProto.newBuilder()
            .setName("payload")
            .setNumber(2)
            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING);
    var file =
        DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("test-echo.proto")
            .setPackage("test")
            .addDependency(RequestContext.getDescriptor().getFile().getName())
            .addMessageType(
                DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("Echo")
                    .addField(field)
                    .addField(payload))
            .build();
    descriptor =
        Descriptors.FileDescriptor.buildFrom(
                file, new Descriptors.FileDescriptor[] {RequestContext.getDescriptor().getFile()})
            .findMessageTypeByName("Echo");
  }

  @BeforeEach
  void setup() throws Exception {
    pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    consumed = ConcurrentHashMap.newKeySet();
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test-key", pair.getPrivate());
          }

          public PublicKey verificationKey(String issuer, String kid) {
            return "test-key".equals(kid) ? pair.getPublic() : null;
          }
        };
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.fromSupplier(() -> consumed.add(issuer + ":" + jti));
    RpcContextSecurity.Authority authority =
        (context, rpc, policy) -> Mono.just(allowed && RPC.equals(rpc));
    sender = new RpcContextSecurity("a", clock, keys, replay, authority);
    recipient = new RpcContextSecurity("b", clock, keys, replay, authority);
  }

  RequestContext context() {
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setActorUserId(ACTOR)
                .setTenantId(TENANT)
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setActorGeneration("1")
                .setMembershipGeneration("2")
                .addScopes("write")
                .setAction("test.write")
                .setIssuerService("a")
                .setAudienceService("b")
                .setIssuedAt(NOW.toString())
                .setExpiresAt(NOW.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }

  static DynamicMessage message(RequestContext c, String payload) {
    return DynamicMessage.newBuilder(descriptor)
        .setField(descriptor.findFieldByName("context"), c)
        .setField(descriptor.findFieldByName("payload"), payload)
        .build();
  }

  String sign(RequestContext c, Message message) {
    return sender.sign(sender.authorize(c, RPC, POLICY).block(), message, RPC, POLICY).block();
  }

  void rejects(Mono<?> result, Status.Code expected) {
    StepVerifier.create(result)
        .expectErrorSatisfies(e -> assertEquals(expected, Status.fromThrowable(e).getCode()))
        .verify();
  }
}
