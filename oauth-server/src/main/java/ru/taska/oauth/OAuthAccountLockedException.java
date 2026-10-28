package ru.taska.oauth;

import java.time.Instant;
import org.springframework.security.authentication.LockedException;

final class OAuthAccountLockedException extends LockedException {
  private final Instant until;
  OAuthAccountLockedException(Instant until) { super("Account is temporarily locked"); this.until = until; }
  Instant until() { return until; }
}
