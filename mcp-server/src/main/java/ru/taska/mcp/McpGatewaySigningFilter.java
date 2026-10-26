package ru.taska.mcp;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import static ru.taska.security.McpGatewaySignature.*;

/** Signs only server-controlled outgoing requests; client metadata cannot request a channel. */
@Component
public class McpGatewaySigningFilter implements ExchangeFilterFunction {
    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public McpGatewaySigningFilter(@Value("${taska.mcp.gateway-signing-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    McpGatewaySigningFilter(String secret, Clock clock) {
        this.key = secret(secret);
        this.clock = clock;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> {
            String authorization = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization == null) return next.exchange(request); // Internal readiness is unsigned.
            String requestId = request.headers().getFirst(REQUEST_ID_HEADER);
            if (!authorization.startsWith("Bearer ") || requestId == null || requestId.isBlank())
                return Mono.error(new IllegalStateException("MCP upstream authentication context is missing"));
            byte[] nonceBytes = new byte[16];
            random.nextBytes(nonceBytes);
            String nonce = encode(nonceBytes), timestamp = Long.toString(clock.instant().getEpochSecond());
            String signature = encode(sign(key, request.method().name(), request.url(), requestId, timestamp, nonce,
                    authorization.substring("Bearer ".length())));
            var signed = ClientRequest.from(request).headers(headers -> {
                headers.set(VERSION_HEADER, VERSION);
                headers.set(TIMESTAMP_HEADER, timestamp);
                headers.set(NONCE_HEADER, nonce);
                headers.set(SIGNATURE_HEADER, signature);
            }).build();
            return next.exchange(signed);
        });
    }
}
