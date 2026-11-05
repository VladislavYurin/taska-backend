package ru.taska.tenant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ru.taska.exception.DomainStatus;

/** Purpose, authority, page size and registry generation are authenticated together. */
final class PlatformFlagCursor {
  private final byte[] key;
  private final Clock clock;

  PlatformFlagCursor(String secret, Clock clock) {
    this.clock = clock;
    try {
      key = Base64.getDecoder().decode(secret);
      if (key.length != 0 && key.length < 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid platform flag cursor key");
    }
  }

  private String sign(String body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private void configured() {
    if (key.length == 0)
      throw TenantRegistryService.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
  }

  String encode(String binding, String after) {
    configured();
    String body =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("flags/v1:"
                        + binding
                        + ":"
                        + after
                        + ":"
                        + clock.instant().plusSeconds(900).getEpochSecond())
                    .getBytes(StandardCharsets.UTF_8));
    return body + "." + sign(body);
  }

  String decode(String token, String binding) {
    configured();
    try {
      if (token.length() > 1024) throw new IllegalArgumentException();
      String[] p = token.split("\\.", -1);
      if (p.length != 2
          || !MessageDigest.isEqual(
              sign(p[0]).getBytes(StandardCharsets.UTF_8), p[1].getBytes(StandardCharsets.UTF_8)))
        throw new IllegalArgumentException();
      String[] v =
          new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8).split(":", -1);
      if (v.length != 4
          || !v[0].equals("flags/v1")
          || !v[1].equals(binding)
          || !v[2].matches("[A-Z][A-Z0-9_]{0,99}")
          || Long.parseLong(v[3]) <= clock.instant().getEpochSecond())
        throw new IllegalArgumentException();
      return v[2];
    } catch (IllegalArgumentException e) {
      throw TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }
}
