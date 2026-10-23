package ru.taska.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable project-defined key; display metadata belongs to the project registry. */
public final class IssueType {
    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    public static final IssueType TASK = new IssueType("TASK");
    public static final IssueType BUG = new IssueType("BUG");
    public static final IssueType STORY = new IssueType("STORY");

    private final String name;

    private IssueType(String name) {
        this.name = name;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static IssueType valueOf(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid issue type key: " + key);
        }
        return switch (key) {
            case "TASK" -> TASK;
            case "BUG" -> BUG;
            case "STORY" -> STORY;
            default -> new IssueType(key);
        };
    }

    @JsonValue
    public String name() {
        return name;
    }

    /** Built-in metadata only. Allowed types must be read from the project registry. */
    public static IssueType[] values() {
        return new IssueType[]{TASK, BUG, STORY};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IssueType type && name.equals(type.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
