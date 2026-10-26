package ru.taska.domain;

/** Authority type returned by auth-service, never inferred from client headers. */
public enum GatewayTokenKind {
  JWT,
  PAT
}
