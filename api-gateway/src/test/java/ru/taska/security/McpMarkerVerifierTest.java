package ru.taska.security;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import ru.taska.domain.ActionChannel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.taska.security.McpGatewaySignature.*;

class McpMarkerVerifierTest {
    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    static final String RID = "request-411", BEARER = "tsk_pat_private_fixture";
    static final URI TARGET = URI.create("http://localhost/api/v1/issues/abc?b=%2F&a=2");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void threeChannelsAndSeparateCredentialAreRequired() {
        var verifier = verifier();
        var plain = MockServerHttpRequest.get(TARGET.toString()).build();
        assertThat(verifier.resolve(plain, RID, BEARER, false)).isEqualTo(ActionChannel.UI);
        assertThat(verifier.resolve(plain, RID, BEARER, true)).isEqualTo(ActionChannel.API_TOKEN);
        assertThat(verifier.resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(1)), RID, BEARER, true)).isEqualTo(ActionChannel.MCP);
        denied(() -> verifier.resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(2)), RID, BEARER, false));
        denied(() -> new McpMarkerVerifier("", 10, CLOCK).resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(3)), RID, BEARER, true));
        denied(() -> verifier.resolve(MockServerHttpRequest.get(TARGET.toString()).header("X-Taska-Source", "MCP").build(), RID, BEARER, true));
    }

    @Test
    void everySignedRequestFieldIsBoundAndFailuresDoNotConsumeNonce() {
        ServerHttpRequest original = signed("POST", TARGET, RID, BEARER, NOW, nonce(1));
        List<ServerHttpRequest> changed = List.of(
                original.mutate().method(HttpMethod.PUT).build(),
                original.mutate().uri(URI.create("http://localhost/api/v1/issues/other?b=%2F&a=2")).build(),
                original.mutate().uri(URI.create("http://localhost/api/v1/issues/abc?a=2&b=%2F")).build(),
                original.mutate().uri(URI.create("http://localhost/api/v1/issues/abc?b=%2f&a=2")).build(),
                original.mutate().headers(h -> h.set(NONCE_HEADER, nonce(2))).build(),
                original.mutate().headers(h -> h.set(TIMESTAMP_HEADER, Long.toString(NOW.plusSeconds(1).getEpochSecond()))).build(),
                original.mutate().headers(h -> h.set(VERSION_HEADER, "2")).build(),
                original.mutate().headers(h -> h.set(SIGNATURE_HEADER, "A".repeat(43))).build(),
                original.mutate().headers(h -> h.add(SIGNATURE_HEADER, "A".repeat(43))).build());
        for (ServerHttpRequest request : changed) {
            var verifier = verifier();
            denied(() -> verifier.resolve(request, RID, BEARER, true));
            assertThat(verifier.resolve(original, RID, BEARER, true)).isEqualTo(ActionChannel.MCP);
        }
        denied(() -> verifier().resolve(original, RID, "other_pat", true));
        var newRequestId = original.mutate().headers(h -> h.set(REQUEST_ID_HEADER, "other-request")).build();
        denied(() -> verifier().resolve(newRequestId, "other-request", BEARER, true));
    }

    @Test
    void expiredFutureAndPartialMarkersAreRejected() {
        denied(() -> verifier().resolve(signed("POST", TARGET, RID, BEARER, NOW.minusSeconds(61), nonce(1)), RID, BEARER, true));
        denied(() -> verifier().resolve(signed("POST", TARGET, RID, BEARER, NOW.plusSeconds(6), nonce(2)), RID, BEARER, true));
        denied(() -> verifier().resolve(MockServerHttpRequest.get(TARGET.toString()).header(VERSION_HEADER, "1").build(), RID, BEARER, true));
    }

    @Test
    void replayIsRejectedEvenForConcurrentRequests() {
        var verifier = verifier();
        var request = signed("POST", TARGET, RID, BEARER, NOW, nonce(1));
        var results = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 12; i++) results.add(CompletableFuture.supplyAsync(() -> {
            try { return verifier.resolve(request, RID, BEARER, true) == ActionChannel.MCP; }
            catch (StatusRuntimeException error) { return false; }
        }));
        assertThat(results.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test
    void cacheIsBoundedWithoutEvictingLiveNoncesAndExpiredEntriesAreReclaimed() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        var verifier = new McpMarkerVerifier(SECRET, 2, clock);
        for (int n : new int[]{1, 2}) assertThat(verifier.resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(n)), RID, BEARER, true)).isEqualTo(ActionChannel.MCP);
        denied(() -> verifier.resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(3)), RID, BEARER, true));
        denied(() -> verifier.resolve(signed("POST", TARGET, RID, BEARER, NOW, nonce(1)), RID, BEARER, true));
        now.set(NOW.plusSeconds(61));
        assertThat(verifier.resolve(signed("POST", TARGET, RID, BEARER, now.get(), nonce(3)), RID, BEARER, true)).isEqualTo(ActionChannel.MCP);
    }

    static MockServerHttpRequest signed(String method, URI uri, String id, String bearer, Instant at, String nonce) {
        String timestamp = Long.toString(at.getEpochSecond());
        return MockServerHttpRequest.method(HttpMethod.valueOf(method), uri).header(REQUEST_ID_HEADER, id)
                .header("Authorization", "Bearer " + bearer).header(VERSION_HEADER, VERSION)
                .header(TIMESTAMP_HEADER, timestamp).header(NONCE_HEADER, nonce)
                .header(SIGNATURE_HEADER, encode(sign(secret(SECRET), method, uri, id, timestamp, nonce, bearer))).build();
    }

    static String nonce(int n) { byte[] value = new byte[16]; value[0] = (byte) n; return encode(value); }
    static McpMarkerVerifier verifier() { return new McpMarkerVerifier(SECRET, 100, CLOCK); }
    static void denied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }
}
