package ru.taska.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryEligibilityTest {
    @Test
    void retryRule_handlesStatusNullTimestampAndStrictBoundary() {
        Instant cutoff = Instant.parse("2026-10-16T10:00:00Z");
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.FAILED, null, cutoff)).isTrue();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.NEW, cutoff.minusSeconds(1), cutoff)).isFalse();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.PUBLISHED, cutoff.minusSeconds(1), cutoff)).isFalse();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.PROCESSING, null, cutoff)).isFalse();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.PROCESSING, cutoff, cutoff)).isFalse();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.PROCESSING, cutoff.plusNanos(1), cutoff)).isFalse();
        assertThat(OutboxRetryEligibility.isRetryable(OutboxStatus.PROCESSING, cutoff.minusNanos(1), cutoff)).isTrue();
    }
}
