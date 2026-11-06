package ru.taska.billing.ingress;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Payloads never appear in receipts, outbox, exception messages or logs. Keys live outside Git. */
public final class InboxCipher {
  public record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {}

  private final String active;
  private final Map<String, byte[]> keys;
  private final SecureRandom random;

  public InboxCipher(String active, Map<String, byte[]> keys, SecureRandom random) {
    if (active == null || !active.matches("[A-Za-z0-9._-]{1,64}") || !keys.containsKey(active))
      throw new IllegalArgumentException("Active inbox payload key required");
    var copy = new java.util.HashMap<String, byte[]>();
    keys.forEach(
        (id, key) -> {
          if (!id.matches("[A-Za-z0-9._-]{1,64}") || key.length != 32)
            throw new IllegalArgumentException("AES-256 inbox payload keys required");
          copy.put(id, key.clone());
        });
    this.active = active;
    this.keys = Map.copyOf(copy);
    this.random = Objects.requireNonNull(random);
  }

  boolean hasKey(String id) {
    return keys.containsKey(id);
  }

  public Sealed seal(UUID id, byte[] body) {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    return new Sealed(active, nonce, crypt(Cipher.ENCRYPT_MODE, active, id, nonce, body));
  }

  byte[] open(String keyId, UUID id, byte[] nonce, byte[] body) {
    return crypt(Cipher.DECRYPT_MODE, keyId, id, nonce, body);
  }

  private byte[] crypt(int mode, String keyId, UUID id, byte[] nonce, byte[] body) {
    try {
      byte[] key = keys.get(keyId);
      if (key == null) throw new IllegalArgumentException();
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD((keyId + ":" + id).getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(body);
    } catch (Exception error) {
      throw new IllegalStateException("Inbox payload cryptography failed");
    }
  }
}
