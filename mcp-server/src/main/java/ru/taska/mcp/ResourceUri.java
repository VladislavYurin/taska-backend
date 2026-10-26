package ru.taska.mcp;

import java.net.URI;
import java.util.Locale;

/** Strict custom URIs; no arbitrary network locations, query parameters or filesystem paths. */
public record ResourceUri(boolean issue, String key) {
  public static ResourceUri parse(String value) {
    try {
      if (value == null || value.length() > 160) throw notFound();
      URI uri = URI.create(value);
      if (!"taska".equals(uri.getScheme())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getUserInfo() != null
          || uri.getPort() != -1
          || uri.getRawPath() == null
          || !uri.getRawPath().startsWith("/")) throw notFound();
      boolean issue = "issues".equals(uri.getHost());
      if (!issue && !"projects".equals(uri.getHost())) throw notFound();
      String key = uri.getPath().substring(1).toUpperCase(Locale.ROOT);
      if (!key.matches(issue ? "[A-Z][A-Z0-9_]{0,31}-[1-9][0-9]{0,18}" : "[A-Z][A-Z0-9_]{0,31}"))
        throw notFound();
      return new ResourceUri(issue, key);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
  }

  public String uri() {
    return "taska://" + (issue ? "issues/" : "projects/") + key;
  }

  public static io.modelcontextprotocol.spec.McpError notFound() {
    return io.modelcontextprotocol.spec.McpError.builder(
            io.modelcontextprotocol.spec.McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
        .message("Ресурс не найден или недоступен; попробуйте search_issues.")
        .build();
  }
}
