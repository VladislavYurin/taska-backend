package ru.taska.mcp;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Request-local audit identity. Never accepts summaries, names or arbitrary argument text. */
public final class ToolAuditTarget {
  private final AtomicReference<String> key = new AtomicReference<>();

  public void record(String value) {
    String normalized = safe(value);
    if (normalized != null) key.set(normalized);
  }

  public String key() {
    return key.get();
  }

  static String safe(String value) {
    if (value == null || value.length() > 64) return null;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return normalized.matches("[A-Z][A-Z0-9_]{0,31}(?:-[1-9][0-9]{0,18})?")
            || normalized.matches("[0-9A-F]{8}-(?:[0-9A-F]{4}-){3}[0-9A-F]{12}")
        ? normalized
        : null;
  }
}
