package ru.taska.security.trust;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.security.tenant.ManagedRpcTrust;
import ru.taska.security.tenant.RpcContextSecurity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class GrpcTenantServiceClientTest {
    @ParameterizedTest
    @ValueSource(ints = {5, 7, 14})
    @DisplayName("RPC-отказ сохраняется и не превращается в пустой batch")
    void preservesRpcFailureInsteadOfEmptyMembershipBatch(int code) {
        var channel = mock(ManagedChannel.class);
        var security = mock(RpcContextSecurity.class);
        var trust = mock(ManagedRpcTrust.class);
        var now = Instant.now();
        when(trust.snapshot()).thenReturn(new ManagedRpcTrust.Snapshot(
                1, 1, "test", now, now.plusSeconds(60),
                Map.of("auth-service", new ManagedRpcTrust.Service("1", Set.of())), List.of(), List.of()));
        var failure = Status.fromCodeValue(code).asRuntimeException();
        when(security.authorize(any(RequestContext.class), anyString(), any(RpcContextSecurity.Policy.class)))
                .thenReturn(Mono.error(failure));
        var client = new GrpcTenantServiceClient(channel, () -> security, trust);

        StepVerifier.create(client.batchValidateMemberships(UUID.randomUUID().toString(), List.of(UUID.randomUUID())))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
        verifyNoInteractions(channel);
    }
}
