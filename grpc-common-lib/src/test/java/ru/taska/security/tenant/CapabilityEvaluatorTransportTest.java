package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.CapabilityFacts.Target;

/** Real TLS/JWS boundary, followed by the production evaluator and uncached current facts. */
class CapabilityEvaluatorTransportTest extends RpcContextFixture {
  @TempDir Path certificates;

  @Test
  void signedContextDoesNotTurnPayloadRoleOrUserPatIntoAdministrativeAuthority() throws Exception {
    var tls = new TenantTransportTest();
    tls.certificates = certificates;
    tls.certificates();
    String session = UUID.randomUUID().toString(), credential = UUID.randomUUID().toString();
    var facts =
        new CapabilityTestFacts(
            UUID.fromString(ACTOR),
            UUID.fromString(TENANT),
            Clock.fixed(NOW, ZoneOffset.UTC),
            session,
            credential);
    var evaluator = new CapabilityEvaluator(facts::current, Clock.fixed(NOW, ZoneOffset.UTC));
    var target = new Target(UUID.fromString(TENANT), UUID.randomUUID(), UUID.randomUUID(), null);
    var method =
        MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(RPC)
            .setRequestMarshaller(
                ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor)))
            .setResponseMarshaller(
                ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor)))
            .build();
    var definition =
        ServerServiceDefinition.builder("test.Echo")
            .addMethod(
                method,
                ServerCalls.<DynamicMessage, DynamicMessage>asyncUnaryCall(
                    (request, observer) ->
                        RpcContextBridge.inbound(evaluator.evaluate(Action.ISSUE_UPDATE, target))
                            .subscribe(
                                decision -> {
                                  observer.onNext(
                                      message(
                                          RpcContextSecurity.context(request),
                                          decision.capability().getAllowed()
                                              + ":"
                                              + decision.capability().getDenialCodesList()));
                                  observer.onCompleted();
                                },
                                observer::onError)))
            .build();
    Server server =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        certificates.resolve("b.crt").toFile(),
                        certificates.resolve("b.key").toFile())
                    .trustManager(certificates.resolve("ca.crt").toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    definition,
                    new TenantServerInterceptor(recipient, Map.of(RPC, POLICY), "test")))
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("localhost", server.getPort())
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(certificates.resolve("ca.crt").toFile())
                    .keyManager(
                        certificates.resolve("a.crt").toFile(),
                        certificates.resolve("a.key").toFile())
                    .build())
            .build();
    try {
      java.util.function.Function<CredentialKind, String> call =
          kind -> {
            var c =
                context().toBuilder()
                    .setContext(
                        context().getContext().toBuilder()
                            .setCredentialKind(kind)
                            .setSessionId(session)
                            .setCredentialId(credential))
                    .build();
            var request = message(c, "{\"role\":\"GLOBAL_ADMIN\",\"bypass\":true}");
            var metadata = new Metadata();
            metadata.put(TenantServerInterceptor.ASSERTION, sign(c, request));
            var response =
                ClientCalls.blockingUnaryCall(
                    ClientInterceptors.intercept(
                        channel, MetadataUtils.newAttachHeadersInterceptor(metadata)),
                    method,
                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                    request);
            return response.getField(descriptor.findFieldByName("payload")).toString();
          };
      assertEquals("true:[]", call.apply(CredentialKind.CREDENTIAL_KIND_WEB_TENANT));
      facts.membershipState = Membership.StateValue.STATE_SUSPENDED;
      assertTrue(
          call.apply(CredentialKind.CREDENTIAL_KIND_WEB_TENANT).contains("MEMBERSHIP_INACTIVE"));
      facts.membershipState = Membership.StateValue.STATE_ACTIVE;
      facts.globalAdmin = true;
      facts.restrictions = Set.of(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE);
      assertTrue(call.apply(CredentialKind.CREDENTIAL_KIND_USER_PAT).contains("READ_ONLY"));
      // Even signed ADMIN-kind claims do not replace the current primary grant record.
      assertTrue(
          call.apply(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT).contains("ADMIN_SCOPE_DENIED"));
      assertTrue(call.apply(CredentialKind.CREDENTIAL_KIND_SERVICE).contains("SCOPE_DENIED"));
      assertEquals(5, facts.reads.get());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
