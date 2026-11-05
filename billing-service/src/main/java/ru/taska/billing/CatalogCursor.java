package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.invalid;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ru.taska.exception.DomainStatus;

final class CatalogCursor {
  private final byte[] key;
  private final Clock clock;

  CatalogCursor(String key, Clock clock) {
    this.clock = clock;
    try {
      this.key = Base64.getDecoder().decode(key);
      if (!key.isEmpty() && this.key.length < 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid catalog cursor key");
    }
  }

  void configured() {
    if (key.length == 0)
      throw CatalogErrors.fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
  }

  private String sign(String text) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  String encode(String code, int limit) {
    configured();
    String body =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("1:"
                        + limit
                        + ":"
                        + code
                        + ":"
                        + clock.instant().plusSeconds(900).getEpochSecond())
                    .getBytes(StandardCharsets.UTF_8));
    return body + "." + sign(body);
  }

  String decode(String cursor, int limit) {
    configured();
    try {
      if (cursor.length() > 512) throw invalid();
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(
              sign(parts[0]).getBytes(StandardCharsets.UTF_8),
              parts[1].getBytes(StandardCharsets.UTF_8))) throw invalid();
      String[] values =
          new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
              .split(":", -1);
      if (values.length != 4
          || !values[0].equals("1")
          || !values[1].equals(Integer.toString(limit))
          || !values[2].matches("[A-Z][A-Z0-9_]{0,63}")
          || Long.parseLong(values[3]) <= clock.instant().getEpochSecond()) throw invalid();
      return values[2];
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }
}
