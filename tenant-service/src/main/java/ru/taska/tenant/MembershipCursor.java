package ru.taska.tenant;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ru.taska.exception.DomainStatus;

/** Private versioned HMAC cursor. Authorization is rechecked against the database on each page. */
public final class MembershipCursor {
  public record Position(long generation, Instant joinedAt, UUID userId) {}

  private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
  private final byte[] secret;
  private final Clock clock;

  public MembershipCursor(String base64Key, Clock clock) {
    this.clock = clock;
    try {
      secret = base64Key.isBlank() ? null : Base64.getDecoder().decode(base64Key);
      if (secret != null && secret.length < 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "Membership cursor key must be Base64 with at least 32 bytes");
    }
  }

  public void requireConfigured() {
    if (secret == null)
      throw TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
  }

  private byte[] mac(byte[] bytes) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(bytes);
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException(error);
    }
  }

  public String encode(String binding, long generation, Instant joinedAt, UUID user) {
    requireConfigured();
    try {
      var buffer = new ByteArrayOutputStream();
      var out = new DataOutputStream(buffer);
      out.writeInt(1);
      out.writeUTF(binding);
      out.writeLong(generation);
      out.writeLong(joinedAt.getEpochSecond());
      out.writeInt(joinedAt.getNano());
      out.writeLong(user.getMostSignificantBits());
      out.writeLong(user.getLeastSignificantBits());
      out.writeLong(clock.instant().plusSeconds(900).getEpochSecond());
      out.flush();
      byte[] bytes = buffer.toByteArray();
      return ENCODE.encodeToString(bytes) + "." + ENCODE.encodeToString(mac(bytes));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  public Position decode(String token, String binding) {
    requireConfigured();
    try {
      if (token.length() > 1024) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      byte[] bytes = DECODE.decode(parts[0]), signature = DECODE.decode(parts[1]);
      if (!ENCODE.encodeToString(bytes).equals(parts[0])
          || !ENCODE.encodeToString(signature).equals(parts[1])
          || !MessageDigest.isEqual(mac(bytes), signature)) throw new IllegalArgumentException();
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readInt() != 1 || !binding.equals(in.readUTF())) throw new IllegalArgumentException();
      long generation = in.readLong(), seconds = in.readLong();
      int nanos = in.readInt();
      UUID user = new UUID(in.readLong(), in.readLong());
      long expiry = in.readLong();
      if (generation < 1
          || nanos < 0
          || nanos > 999999999
          || in.available() != 0
          || expiry <= clock.instant().getEpochSecond()) throw new IllegalArgumentException();
      Instant joined = Instant.ofEpochSecond(seconds, nanos);
      joined.atOffset(ZoneOffset.UTC);
      return new Position(generation, joined, user);
    } catch (IOException | IllegalArgumentException | DateTimeException error) {
      throw TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }
}
