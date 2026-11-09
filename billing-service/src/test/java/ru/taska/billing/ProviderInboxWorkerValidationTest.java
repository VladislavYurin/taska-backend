package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.taska.api.b2b.v1.ReactorPaymentSettlementServiceGrpc;
import ru.taska.api.b2b.v1.ReactorProviderInboxServiceGrpc;
import ru.taska.billing.ingress.GrpcProviderInboxWorker;
import ru.taska.billing.payment.*;
import ru.taska.security.tenant.RpcContextSecurity;

class ProviderInboxWorkerValidationTest {
  final Fixture fixture = new Fixture();

  static class Fixture {
    final ReactorProviderInboxServiceGrpc.ReactorProviderInboxServiceStub inbox =
        mock(ReactorProviderInboxServiceGrpc.ReactorProviderInboxServiceStub.class);
    final ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub settlement =
        mock(ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub.class);
    final RpcContextSecurity trust = mock(RpcContextSecurity.class);
    final GrpcProviderInboxWorker worker =
        new GrpcProviderInboxWorker(inbox, settlement, trust, Clock.systemUTC(), "test-generation");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void missingProviderObjectNeverReachesTrustOrTransport(String payment) {
    var a =
        new PaymentAttemptStore.Attempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TEST",
            "key",
            payment,
            "{}",
            "test-account",
            PaymentProvider.Environment.TEST,
            PaymentAttemptStore.State.UNKNOWN,
            Instant.now(),
            Instant.now(),
            1,
            2000,
            "EUR",
            "v1");
    var error = assertThrows(ProviderException.class, () -> fixture.worker.register(a).block());
    assertEquals(ProviderException.Reason.INVALID_STATE, error.reason());
    verifyNoInteractions(fixture.inbox, fixture.settlement, fixture.trust);
  }

  @Test
  void missingAttemptNeverReachesTrustOrTransport() {
    assertThrows(ProviderException.class, () -> fixture.worker.register(null).block());
    verifyNoInteractions(fixture.inbox, fixture.settlement, fixture.trust);
  }
}
