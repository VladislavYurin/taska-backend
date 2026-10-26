package ru.taska.mcp;

import java.util.Map;

public class GatewayToolException extends RuntimeException {
  private final String code;
  private final Map<String, Object> details;

  public GatewayToolException(String code, String message) {
    this(code, message, Map.of());
  }

  public GatewayToolException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  public Map<String, Object> details() {
    return details;
  }
}
