package ru.taska.security.session;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SessionConfigurationTest {
  @Test
  void missingReceiptKeyFailsWithConfigurationName() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SessionConfiguration()
                    .webSessionStore(null, null, null, "", Duration.ofDays(7)));
    assertTrue(error.getMessage().contains("TASKA_B2B_REFRESH_RECEIPT_KEY"));
  }

  @Test
  void missingSigningKeyFailsWithConfigurationName() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> new SessionConfiguration().tenantAccessIssuer("", "kid", Duration.ofMinutes(15)));
    assertTrue(error.getMessage().contains("TASKA_B2B_ED25519_PRIVATE_KEY"));
  }
}
