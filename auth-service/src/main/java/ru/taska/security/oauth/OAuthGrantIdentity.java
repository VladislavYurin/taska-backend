package ru.taska.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Introspected authorization. The integration grant, its tenant, generation and client are all
 * present for a TAS-498 tenant-bound grant and all absent for a legacy unbound one.
 */
record OAuthGrantIdentity(
    UUID subject,
    UUID grantId,
    UUID tokenId,
    List<String> scopes,
    Instant expiresAt,
    UUID integrationGrant,
    UUID tenant,
    Long grantVersion,
    String clientId) {
  OAuthGrantIdentity(
      UUID subject, UUID grantId, UUID tokenId, List<String> scopes, Instant expiresAt) {
    this(subject, grantId, tokenId, scopes, expiresAt, null, null, null, null);
  }
}
