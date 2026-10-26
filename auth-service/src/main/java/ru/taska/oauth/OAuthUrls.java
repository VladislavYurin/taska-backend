package ru.taska.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class OAuthUrls {
    private OAuthUrls() { }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static String returnTo(String value) {
        if (value == null || value.length() > 1024 || !value.startsWith("/") || value.startsWith("//")) return "/projects";
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || path.startsWith("//")
                    || path.contains("\\") || path.contains("%") || value.contains("\\")
                    || value.codePoints().anyMatch(Character::isISOControl) || path.codePoints().anyMatch(Character::isISOControl)) return "/projects";
            return value;
        } catch (IllegalArgumentException ignored) { return "/projects"; }
    }
    public static String endpoint(String base, String path) {
        URI uri = URI.create(base);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) throw new IllegalArgumentException("OAuth base URL must use HTTPS");
        return uri.resolve(path).toString();
    }
}
