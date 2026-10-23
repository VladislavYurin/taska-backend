package ru.taska.event.payload.authService;

import java.util.UUID;

/** Verified identity email snapshot; synchronizes preferences without a welcome notification. */
public record UserEmailChangedPayload(UUID userId, String email) { }
