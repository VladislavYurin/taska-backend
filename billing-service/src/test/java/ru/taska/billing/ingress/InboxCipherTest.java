package ru.taska.billing.ingress;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxCipherTest {
  @Test
  void rotationRetainsOldPayloadAndBindsCiphertextToReceiptAndKeyId() {
    byte[] first = new byte[32], second = new byte[32];
    second[0] = 1;
    var old = new InboxCipher("first", Map.of("first", first), new SecureRandom());
    UUID id = UUID.randomUUID();
    byte[] payload = "private fixture body".getBytes(StandardCharsets.UTF_8);
    var sealed = old.seal(id, payload);
    var rotated =
        new InboxCipher("second", Map.of("first", first, "second", second), new SecureRandom());
    assertArrayEquals(
        payload, rotated.open(sealed.keyId(), id, sealed.nonce(), sealed.ciphertext()));
    assertThrows(
        IllegalStateException.class,
        () -> rotated.open(sealed.keyId(), UUID.randomUUID(), sealed.nonce(), sealed.ciphertext()));
    assertThrows(
        IllegalStateException.class,
        () -> rotated.open("second", id, sealed.nonce(), sealed.ciphertext()));
    assertEquals("second", rotated.seal(id, payload).keyId());
  }

  @Test
  void unavailableOrWeakKeysFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InboxCipher("missing", Map.of(), new SecureRandom()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InboxCipher("weak", Map.of("weak", new byte[16]), new SecureRandom()));
    assertThrows(
        IllegalStateException.class,
        () -> new ProviderInboxConfiguration().inboxCipher("active", ""));
    var generation =
        assertThrows(
            IllegalStateException.class,
            () -> new ProviderInboxConfiguration().providerInboxWorker(null, null, null, ""));
    assertTrue(generation.getMessage().contains("TASKA_BILLING_INBOX_SERVICE_GENERATION"));
  }
}
