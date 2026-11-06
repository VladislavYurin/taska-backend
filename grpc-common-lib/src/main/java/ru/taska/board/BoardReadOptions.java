package ru.taska.board;

import java.time.Instant;
import java.util.List;

/** Explicit board filters/order; null order keys retain the legacy per-type workflow decision. */
public record BoardReadOptions(Instant doneSince, List<String> updatedOrderStatusKeys) {
    public static final BoardReadOptions DEFAULT = new BoardReadOptions(null, null);

    public BoardReadOptions {
        if (updatedOrderStatusKeys != null)
            updatedOrderStatusKeys = List.copyOf(updatedOrderStatusKeys);
    }

    public List<String> orderKeys(List<String> doneStatusKeys) {
        return updatedOrderStatusKeys == null
                ? (doneStatusKeys == null ? List.of() : doneStatusKeys)
                : updatedOrderStatusKeys;
    }
}
