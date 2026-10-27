package ru.taska.domain.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Presence-aware validation shared by workflow binding commands. */
final class WorkflowCommandValidation {
    private WorkflowCommandValidation() {}

    static List<String> issueTypes(List<String> values) {
        if (values == null || values.stream().anyMatch(value -> value == null || !value.matches("^[A-Z][A-Z0-9_]{1,31}$"))
                || new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("issueTypes must contain distinct project type keys");
        }
        // An explicit empty set reaches the backend so its stable details.reason is preserved.
        return List.copyOf(values);
    }

    static Map<String, String> statusMappings(Map<String, String> values) {
        if (values == null || values.entrySet().stream().anyMatch(entry -> !statusKey(entry.getKey()) || !statusKey(entry.getValue()))) {
            throw new IllegalArgumentException("statusMappings must contain non-null status keys");
        }
        return Map.copyOf(values);
    }

    private static boolean statusKey(String value) {
        return value != null && value.matches("^[A-Z][A-Z0-9_]{1,63}$");
    }
}
