package ru.taska.mcp;

import java.util.Locale;
import java.util.UUID;

/** Correlation values are untrusted and must never transport credentials into downstream logs. */
final class McpRequestIds {
  private McpRequestIds() {}

  static boolean safe(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) return false;
    String lower = value.toLowerCase(Locale.ROOT);
    return !lower.contains("tsk_pat_") && !lower.contains("bearer") && !lower.startsWith("eyj");
  }

  static String resolve(String value) {
    return safe(value) ? value : UUID.randomUUID().toString();
  }
}
