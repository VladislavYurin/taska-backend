package ru.taska.mcp;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.taska.security.McpGatewaySignature.*;

class McpGatewaySigningFilterTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void signedHeadersBindTheFinalEncodedUriAndBearerAndEverySubscriptionGetsFreshNonce() {
        var filter = new McpGatewaySigningFilter(SECRET, CLOCK);
        var request = ClientRequest.create(HttpMethod.POST, URI.create("http://gateway/api/v1/issues/abc?x=%2F&b=2"))
                .header("Authorization", "Bearer tsk_pat_fixture").header(REQUEST_ID_HEADER, "request-411").build();
        List<ClientRequest> forwarded = new ArrayList<>();
        ExchangeFunction next = signed -> {
            forwarded.add(signed);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        var call = filter.filter(request, next);
        call.block(); call.block();
        assertThat(forwarded).hasSize(2);
        assertThat(forwarded.get(0).headers().getFirst(NONCE_HEADER)).isNotEqualTo(forwarded.get(1).headers().getFirst(NONCE_HEADER));
        for (ClientRequest signed : forwarded) {
            assertThat(signed.headers().getFirst(VERSION_HEADER)).isEqualTo("1");
            assertThat(signed.headers().getFirst("Authorization")).isEqualTo("Bearer tsk_pat_fixture");
            assertThat(signed.url()).isEqualTo(request.url());
            String timestamp = signed.headers().getFirst(TIMESTAMP_HEADER), nonce = signed.headers().getFirst(NONCE_HEADER);
            assertThat(nonce).matches("[A-Za-z0-9_-]{22}");
            assertThat(signed.headers().getFirst(SIGNATURE_HEADER)).isEqualTo(encode(sign(secret(SECRET), "POST",
                    signed.url(), "request-411", timestamp, nonce, "tsk_pat_fixture")));
        }
    }

    @Test
    void missingServerCredentialStopsAuthenticatedUpstreamButDoesNotBreakHealth() {
        var filter = new McpGatewaySigningFilter("", CLOCK);
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction next = request -> { calls.incrementAndGet(); return Mono.just(ClientResponse.create(HttpStatus.OK).build()); };
        var protectedRequest = ClientRequest.create(HttpMethod.GET, URI.create("http://gateway/api/v1/users/me"))
                .header("Authorization", "Bearer tsk_pat_fixture").header(REQUEST_ID_HEADER, "request-411").build();
        StepVerifier.create(filter.filter(protectedRequest, next)).expectError(IllegalStateException.class).verify();
        assertThat(calls).hasValue(0);
        filter.filter(ClientRequest.create(HttpMethod.GET, URI.create("http://gateway/actuator/health")).build(), next).block();
        assertThat(calls).hasValue(1);
    }
}
