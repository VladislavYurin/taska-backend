package ru.taska.exception;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainExceptionTest {
    @Test
    void detailsAreAnImmutableSnapshotAndOldConstructorRemainsValid() {
        var details = new HashMap<String, String>();
        details.put("lockedUntil", "2026-09-22T11:55:50Z");
        var error = new DomainException(DomainStatus.PERMISSION_DENIED, "Locked", details);
        details.clear();
        assertThat(error.getDetails()).containsEntry("lockedUntil", "2026-09-22T11:55:50Z");
        assertThatThrownBy(() -> error.getDetails().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new DomainException(DomainStatus.NOT_FOUND, "Missing").getDetails()).isEmpty();
    }
}
