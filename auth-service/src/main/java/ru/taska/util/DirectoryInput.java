package ru.taska.util;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class DirectoryInput {
    private final Validator validator;
    private record Address(@NotBlank @Email @Size(max = 320) String value) { }

    public String email(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!validator.validate(new Address(normalized)).isEmpty()) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "A valid email address is required (maximum 320 characters)");
        }
        return normalized;
    }

    public String displayName(String value, String email) {
        String name = value == null || value.isBlank() ? email.substring(0, email.indexOf('@')) : value.strip();
        if (name.codePointCount(0, name.length()) > 128 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Display name must contain at most 128 characters and no control characters");
        }
        return name;
    }
}
