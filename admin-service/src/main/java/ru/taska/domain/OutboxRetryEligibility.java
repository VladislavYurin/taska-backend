package ru.taska.domain;

import java.time.Instant;

/** Единое правило пригодности к retry для сводки и команды повтора. */
public final class OutboxRetryEligibility {
    private OutboxRetryEligibility() {
    }

    public static boolean isRetryable(OutboxStatus status, Instant processingStartedAt, Instant stuckBefore) {
        return status == OutboxStatus.FAILED
                || (status == OutboxStatus.PROCESSING && processingStartedAt != null
                && processingStartedAt.isBefore(stuckBefore));
    }
}
