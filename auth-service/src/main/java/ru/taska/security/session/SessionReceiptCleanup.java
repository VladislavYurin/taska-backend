package ru.taska.security.session;

import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Erase encrypted secrets after the replay window while retaining dedupe keys. */
@Component
@ConditionalOnProperty(name = "taska.b2b.sessions.enabled", havingValue = "true")
public final class SessionReceiptCleanup {
  private static final Logger log = LoggerFactory.getLogger(SessionReceiptCleanup.class);
  private final DatabaseClient db;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

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
    if (!running.compareAndSet(false, true)) return;
    purge().timeout(Duration.ofSeconds(30))
        .doFinally(ignored -> running.set(false))
        .subscribe(ignored -> {}, error -> log.warn("Session receipt cleanup failed"));
  }
}
