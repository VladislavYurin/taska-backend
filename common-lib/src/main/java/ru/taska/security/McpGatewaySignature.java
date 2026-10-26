package ru.taska.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned server identity; it does not grant user or project permissions. */
public final class McpGatewaySignature {
    public static final String VERSION = "1";
    public static final String VERSION_HEADER = "X-Taska-Mcp-Version";
    public static final String TIMESTAMP_HEADER = "X-Taska-Mcp-Timestamp";
    public static final String NONCE_HEADER = "X-Taska-Mcp-Nonce";
    public static final String SIGNATURE_HEADER = "X-Taska-Mcp-Signature";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final long TTL_SECONDS = 60;

    private McpGatewaySignature() {}

    public static byte[] secret(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length < 32) throw new IllegalArgumentException("short key");
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("MCP gateway signing secret must be base64 of at least 32 bytes");
        }
    }

    public static String rawTarget(URI uri) {
        String path = uri.getRawPath();
        return (path == null || path.isEmpty() ? "/" : path)
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    public static byte[] sign(byte[] secret, String method, URI uri, String requestId,
                              String timestamp, String nonce, String bearer) {
        if (secret == null) throw new IllegalStateException("MCP gateway identity is not configured");
        String[] fields = {VERSION, method, rawTarget(uri), requestId, timestamp, nonce, bearerHash(bearer)};
        for (String field : fields) {
            if (field == null || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid MCP signature input");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(String.join("\n", fields).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("MCP signature algorithm is unavailable", error);
        }
    }

    public static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    private static String bearerHash(String bearer) {
        if (bearer == null || bearer.isEmpty()) throw new IllegalArgumentException("Missing Bearer");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bearer.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
