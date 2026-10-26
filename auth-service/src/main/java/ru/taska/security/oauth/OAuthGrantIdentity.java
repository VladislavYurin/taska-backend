package ru.taska.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OAuthGrantIdentity(
    UUID subject, UUID grantId, UUID tokenId, List<String> scopes, Instant expiresAt) {}
