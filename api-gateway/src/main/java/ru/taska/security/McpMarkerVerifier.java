package ru.taska.security;

import io.grpc.Status;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import ru.taska.domain.ActionChannel;

import static ru.taska.security.McpGatewaySignature.*;

public class McpMarkerVerifier {
    private final byte[] key;
    private final int maxNonces;
    private final Clock clock;
    private final Map<String, Long> nonces = new HashMap<>();

    @Autowired
    public McpMarkerVerifier(@Value("${gateway.mcp-marker.signing-secret:}") String secret,
                             @Value("${gateway.mcp-marker.max-nonces:10000}") int maxNonces) {
        this(secret, maxNonces, Clock.systemUTC());
    }

    McpMarkerVerifier(String secret, int maxNonces, Clock clock) {
        this.key = secret(secret);
        if (maxNonces < 1) throw new IllegalArgumentException("MCP nonce cache must be bounded and positive");
        this.maxNonces = maxNonces;
        this.clock = clock;
    }

    public boolean hasMarker(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        return List.of(VERSION_HEADER, TIMESTAMP_HEADER, NONCE_HEADER, SIGNATURE_HEADER).stream().anyMatch(headers::containsHeader)
                || headers.getOrEmpty("X-Taska-Source").stream().anyMatch("MCP"::equalsIgnoreCase);
    }

    public ActionChannel resolve(ServerHttpRequest request, String requestId, String bearer, boolean pat) {
        if (!hasMarker(request)) return pat ? ActionChannel.API_TOKEN : ActionChannel.UI;
        if (!pat || key == null) throw rejected();
        try {
            HttpHeaders headers = request.getHeaders();
            if (!VERSION.equals(one(headers, VERSION_HEADER))) throw rejected();
            String timestamp = one(headers, TIMESTAMP_HEADER), nonce = one(headers, NONCE_HEADER);
            String signature = one(headers, SIGNATURE_HEADER);
            if (!timestamp.matches("[0-9]{1,12}") || !nonce.matches("[A-Za-z0-9_-]{22}")
                    || !signature.matches("[A-Za-z0-9_-]{43}")
                    || !requestId.equals(one(headers, REQUEST_ID_HEADER))) throw rejected();
            long issuedAt = Long.parseLong(timestamp), now = clock.instant().getEpochSecond();
            // Small forward clock skew; expiry is still exactly issuedAt + 60 seconds.
            if (issuedAt > now + 5 || now - issuedAt > TTL_SECONDS) throw rejected();
            byte[] provided = Base64.getUrlDecoder().decode(signature);
            byte[] expected = sign(key, request.getMethod().name(), request.getURI(), requestId, timestamp, nonce, bearer);
            if (provided.length != 32 || !MessageDigest.isEqual(expected, provided)) throw rejected();
            recordNonce(nonce, issuedAt + TTL_SECONDS, now);
            return ActionChannel.MCP;
        } catch (IllegalArgumentException error) {
            throw rejected();
        }
    }

    public static io.grpc.StatusRuntimeException rejected() {
        return Status.PERMISSION_DENIED.withDescription("Invalid MCP server marker")
                .asRuntimeException(mapper.GrpcErrorDetails.encode(Map.of("reason", "INVALID_MCP_MARKER")));
    }

    private static String one(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) throw rejected();
        return values.getFirst();
    }

    private synchronized void recordNonce(String nonce, long expiresAt, long now) {
        nonces.values().removeIf(expiry -> expiry < now);
        if (nonces.containsKey(nonce) || nonces.size() >= maxNonces) throw rejected();
        nonces.put(nonce, expiresAt);
    }
}
