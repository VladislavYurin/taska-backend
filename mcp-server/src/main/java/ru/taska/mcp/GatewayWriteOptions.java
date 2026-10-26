package ru.taska.mcp;

import java.util.UUID;

/** The only supported mutation headers. User-supplied header names are never accepted. */
public record GatewayWriteOptions(Integer version, UUID idempotencyKey) {
  public GatewayWriteOptions {
    if (version != null && version < 1)
      throw ToolArguments.invalid("version должна быть положительной.");
  }

  public static GatewayWriteOptions none() {
    return new GatewayWriteOptions(null, null);
  }

  public static GatewayWriteOptions version(int value) {
    return new GatewayWriteOptions(value, null);
  }

  public static GatewayWriteOptions create(String value) {
    return new GatewayWriteOptions(null, UUID.fromString(value));
  }
}
