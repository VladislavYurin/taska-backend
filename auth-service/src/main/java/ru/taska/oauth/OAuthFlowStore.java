package ru.taska.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Repository
@Slf4j
@RequiredArgsConstructor
public class OAuthFlowStore {
    private final DatabaseClient database;
    private final Clock clock;
    private static final SecureRandom RANDOM = new SecureRandom();
    public record State(String provider, String nonce, String verifier, String returnTo) { }
    public record Grant(OAuthIdentity identity, String returnTo) { }
    public static String random() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    public static String hash(String value) { return DigestUtils.sha256Hex(value); }
    public static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    public Mono<Void> saveState(String state, String browser, State value) {
        return database.sql("INSERT INTO taska.oauth_states(state_hash,browser_hash,provider,nonce,verifier,return_to,expires_at) VALUES(:state,:browser,:provider,:nonce,:verifier,:returnTo,:expiry)")
                .bind("state", hash(state)).bind("browser", hash(browser)).bind("provider", value.provider())
                .bind("nonce", value.nonce()).bind("verifier", value.verifier()).bind("returnTo", value.returnTo())
                .bind("expiry", clock.instant().plus(Duration.ofMinutes(10))).fetch().rowsUpdated().then();
    }
    /** Atomic, browser-bound claim. Invalid cookies do not consume a legitimate state. */
    public Mono<State> claimState(String provider, String state, String browser) {
        if (!valid(state) || !valid(browser)) return Mono.error(OAuthErrors.failure("STATE_INVALID"));
        return database.sql("DELETE FROM taska.oauth_states WHERE state_hash=:state AND browser_hash=:browser AND provider=:provider AND expires_at>now() RETURNING provider,nonce,verifier,return_to")
                .bind("state", hash(state)).bind("browser", hash(browser)).bind("provider", provider)
                .map((row, metadata) -> new State(row.get("provider", String.class), row.get("nonce", String.class), row.get("verifier", String.class), row.get("return_to", String.class)))
                .one().switchIfEmpty(Mono.error(OAuthErrors.failure("STATE_INVALID")));
    }
    public Mono<String> grant(String kind, OAuthIdentity identity, String returnTo) {
        return Mono.defer(() -> {
            String raw = random();
            var query = database.sql("INSERT INTO taska.oauth_grants(code_hash,kind,provider,subject,email,suggested_name,return_to,expires_at) VALUES(:hash,:kind,:provider,:subject,:email,:name,:returnTo,:expiry)")
                    .bind("hash", hash(raw)).bind("kind", kind).bind("provider", identity.provider()).bind("subject", identity.subject())
                    .bind("email", identity.email()).bind("returnTo", returnTo)
                    .bind("expiry", clock.instant().plus(kind.equals("SIGNUP") ? Duration.ofMinutes(15) : Duration.ofSeconds(60)));
            query = identity.suggestedDisplayName() == null ? query.bindNull("name", String.class) : query.bind("name", identity.suggestedDisplayName());
            return query.fetch().rowsUpdated().thenReturn(raw);
        });
    }
    public Mono<Grant> claimGrant(String kind, String code) {
        if (!valid(code)) return Mono.error(OAuthErrors.failure("STATE_INVALID"));
        return database.sql("DELETE FROM taska.oauth_grants WHERE code_hash=:hash AND kind=:kind AND expires_at>now() RETURNING provider,subject,email,suggested_name,return_to")
                .bind("hash", hash(code)).bind("kind", kind).map((row, metadata) -> new Grant(new OAuthIdentity(row.get("provider", String.class), row.get("subject", String.class), row.get("email", String.class), row.get("suggested_name", String.class)), row.get("return_to", String.class)))
                .one().switchIfEmpty(Mono.error(OAuthErrors.failure("STATE_INVALID")));
    }
    @Scheduled(fixedDelayString = "${taska.oauth.cleanup-interval:3600000}")
    public void cleanup() {
        database.sql("DELETE FROM taska.oauth_states WHERE expires_at<now()-interval '1 hour'").fetch().rowsUpdated()
                .then(database.sql("DELETE FROM taska.oauth_grants WHERE expires_at<now()-interval '1 hour'").fetch().rowsUpdated())
                .subscribe(ignored -> {}, ignored -> log.warn("OAuth flow expiry cleanup failed"));
    }
}
