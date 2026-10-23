package ru.taska.service;

import java.util.Optional;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

public final class ProjectInputValidator {
    private ProjectInputValidator() { }

    public static void validate(Optional<String> name, Optional<String> description, Optional<String> color) {
        name.ifPresent(value -> {
            if (value.isBlank() || value.length() > 255) invalid("name must be nonblank and at most 255 characters");
        });
        description.ifPresent(value -> {
            if (value.length() > 2000) invalid("description must be at most 2000 characters");
        });
        color.ifPresent(value -> {
            if (!value.matches("^#[0-9A-Fa-f]{6}$")) invalid("color must be a six-digit HEX color");
        });
    }

    private static void invalid(String message) {
        throw new DomainException(DomainStatus.INVALID_ARGUMENT, message);
    }
}
