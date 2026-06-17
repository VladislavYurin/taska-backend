package ru.taska.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.config.props.PasswordPolicyProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/**
 * Валидация паролей на пустоту, длину и надежность
 * <p>Работает с codePoints для обеспечиния безопасности non-latin паролей.
 * @see PasswordPolicyProperties
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordPolicyValidator implements Validator<String> {

    private final PasswordPolicyProperties props;

    /**
     * Валидация переданного пароля
     * @throws ru.taska.exception.DomainException
     */
    @Override
    public void validate(String password) {
        log.debug("Starting password validation with policy: min={}, max={}, requireUpper={}, requireDigit={}, requireSpecial={}",
                props.min(), props.max(), props.requireUpper(), props.requireDigit(), props.requireSpecial()
                );

        checkBlank(password);
        checkLength(password);
        checkStrength(password);

        log.debug("Password validation completed successfully");
    }

    private void checkBlank(String password) {
        log.debug("Enter password blank validation");

        if (password == null || password.isBlank()) {
            log.debug("Password validation failed: null or blank");

            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Password is required");
        }

        log.debug("Password blank validation passed");
    }

    private void checkLength(String password) {
        log.debug("Enter password length validation");

        int len = password.codePointCount(0, password.length());
        if (len < props.min() || len > props.max()) {

            log.debug("Password validation failed: length={}, min={}, max={}",
                    len, props.min(), props.max()
                    );

            throw new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    String.format("Password must be at least %d characters and not exceed %d characters",
                            props.min(), props.max()));
        }

        log.debug("Password length validation passed: length={}", len);
    }

    private void checkStrength(String password) {
        log.debug("Enter password strength validation");

        if (props.requireUpper() && !hasUpper(password)) {
            log.debug("Password validation failed: required upper character");

            throw new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Password must have at least 1 upper character"
            );
        }

        if (props.requireDigit() && !hasDigit(password)) {
            log.debug("Password validation failed: required digit character");

            throw new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Password must have at least 1 digit character"
            );
        }

        if (props.requireSpecial() && !hasSpecial(password)) {
            log.debug("Password validation failed: required special character");

            throw new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Password must have at least 1 special character"
            );
        }

        log.debug("Password strength validation passed");
    }

    private static boolean hasUpper(String password) {
        return password.codePoints().anyMatch(Character::isUpperCase);
    }

    private static boolean hasDigit(String password) {
        return password.codePoints().anyMatch(Character::isDigit);
    }

    /**
     * Проверяет наличие минимум одного специального символа в пароле
     * <p> Специальным символом считается символ не являющийся буквой, цифрой или пробелом
     */
    private static boolean hasSpecial(String password) {
        return password.codePoints().anyMatch(
                cp -> !Character.isLetterOrDigit(cp)
                        && !Character.isWhitespace(cp)
        );
    }
}
