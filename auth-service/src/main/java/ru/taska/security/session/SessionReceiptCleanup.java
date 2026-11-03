package ru.taska.security.session;

import java.time.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Erase encrypted secrets after the replay window while retaining dedupe keys. */
@Component
@ConditionalOnProperty(name = "taska.b2b.sessions.enabled", havingValue = "true")
public final class SessionReceiptCleanup {
  private final DatabaseClient db;
  private final Clock clock;

  public SessionReceiptCleanup(DatabaseClient db) {
    this(db, Clock.systemUTC());
  }

  SessionReceiptCleanup(DatabaseClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  Mono<Void> purge() {
    var now = clock.instant().atOffset(ZoneOffset.UTC);
    return db.sql("UPDATE taska.refresh_rotation_receipts SET nonce=NULL,ciphertext=NULL"
        + " WHERE expires_at<=:now AND ciphertext IS NOT NULL")
        .bind("now", now).fetch().rowsUpdated()
        .then(db.sql("UPDATE taska.session_issuance_receipts SET nonce=NULL,ciphertext=NULL"
            + " WHERE expires_at<=:now AND ciphertext IS NOT NULL")
            .bind("now", now).fetch().rowsUpdated()).then();
  }

  @Scheduled(fixedDelayString = "${taska.b2b.sessions.receipt-cleanup-interval:1m}")
  public void run() {
    purge().block(Duration.ofSeconds(30));
  }
}
