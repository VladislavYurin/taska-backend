package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** DELETE rechecks the user row under its write lock, so concurrent activation is preserved. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignupCleanup {
    private final DatabaseClient database;
    public Mono<Long> cleanup() {
        return database.sql("""
                DELETE FROM taska.users u WHERE u.id IN (
                    SELECT candidate.id FROM taska.users candidate
                    WHERE candidate.status='INVITED' AND candidate.created_via='SIGNUP'
                      AND EXISTS (SELECT 1 FROM taska.invite_tokens t WHERE t.user_id=candidate.id)
                      AND NOT EXISTS (SELECT 1 FROM taska.invite_tokens t WHERE t.user_id=candidate.id AND t.expires_at>now())
                    ORDER BY candidate.id LIMIT 100 FOR UPDATE SKIP LOCKED
                ) AND u.status='INVITED' AND u.created_via='SIGNUP'
                """).fetch().rowsUpdated()
                .flatMap(count -> database.sql("DELETE FROM taska.signup_rate_limits WHERE started_at<now()-interval '2 hours'")
                        .fetch().rowsUpdated().thenReturn(count));
    }
    @Scheduled(fixedDelayString="${taska.signup.cleanup-interval:1m}")
    public void scheduled() {
        cleanup().subscribe(count -> { }, error -> log.warn("Signup cleanup failed: {}", error.getClass().getSimpleName()));
    }
}
