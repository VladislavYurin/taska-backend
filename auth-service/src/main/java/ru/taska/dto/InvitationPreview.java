package ru.taska.dto;

import java.time.Instant;

public record InvitationPreview(String email, String displayName, String kind, Instant expiresAt) { }
