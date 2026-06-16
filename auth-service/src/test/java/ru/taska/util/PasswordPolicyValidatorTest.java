package ru.taska.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.config.props.PasswordPolicyProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordPolicyValidator Tests")
class PasswordPolicyValidatorTest {

    @Mock
    private PasswordPolicyProperties props;

    private PasswordPolicyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PasswordPolicyValidator(props);

        when(props.min()).thenReturn(8);
        when(props.max()).thenReturn(128);
        when(props.requireUpper()).thenReturn(false);
        when(props.requireDigit()).thenReturn(false);
        when(props.requireSpecial()).thenReturn(false);
    }

    @Nested
    @DisplayName("Blank validation tests")
    class BlankValidationTests {

        @Test
        @DisplayName("Should throw exception when password is null")
        void shouldThrowWhenPasswordIsNull() {
            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(null));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password is required", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception when password is blank")
        void shouldThrowWhenPasswordIsBlank() {
            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate("   "));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password is required", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception when password is empty string")
        void shouldThrowWhenPasswordIsEmpty() {
            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(""));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password is required", exception.getMessage());
        }

        @Test
        @DisplayName("Should not throw when password has spaces inside")
        void shouldNotThrowWhenPasswordHasSpacesInside() {
            String password = "pass word";
            assertDoesNotThrow(() -> validator.validate(password));
        }
    }

    @Nested
    @DisplayName("Length validation tests")
    class LengthValidationTests {

        @Test
        @DisplayName("Should throw exception when password is too short")
        void shouldThrowWhenPasswordIsTooShort() {
            String password = "a".repeat(4);

            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(password));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertTrue(exception.getMessage().contains("at least 8 characters"));
        }

        @Test
        @DisplayName("Should throw exception when password is too long")
        void shouldThrowWhenPasswordIsTooLong() {
            String password = "a".repeat(130);

            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(password));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertTrue(exception.getMessage().contains("not exceed 128 characters"));
        }

        @Test
        @DisplayName("Should accept password with exact min length")
        void shouldAcceptPasswordWithExactMinLength() {
            String password = "a".repeat(8);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should accept password with exact max length")
        void shouldAcceptPasswordWithExactMaxLength() {
            String password = "a".repeat(128);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should correctly count Unicode characters")
        void shouldCorrectlyCountUnicodeCharacters() {
            String password = "а́".repeat(4); // Каждый символ занимает 2 codePoint (4 * 2 = 8)

            assertDoesNotThrow(() -> validator.validate(password));
        }
    }

    @Nested
    @DisplayName("Strength validation tests")
    class StrengthValidationTests {

        @Test
        @DisplayName("Should throw exception when no uppercase required but not present")
        void shouldThrowWhenNoUppercaseRequired() {
            String password = "password";
            when(props.requireUpper()).thenReturn(true);

            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(password));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password must have at least 1 upper character", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw exception when no digit required but not present")
        void shouldThrowWhenNoDigitRequired() {
            String password = "password";
            when(props.requireDigit()).thenReturn(true);

            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(password));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password must have at least 1 digit character", exception.getMessage());
        }

        @Test
        @DisplayName("Should handle password with special Unicode characters")
        void shouldHandlePasswordWithSpecialUnicodeCharacters() {
            String password = "!@#$%^&*()";
            when(props.requireSpecial()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should handle password with emoji")
        void shouldHandlePasswordWithEmoji() {
            String password = "password😊";
            when(props.requireSpecial()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should throw exception when no special required but not present")
        void shouldThrowWhenNoSpecialRequired() {
            String password = "password";
            when(props.requireSpecial()).thenReturn(true);

            DomainException exception = assertThrows(DomainException.class,
                    () -> validator.validate(password));

            assertEquals(DomainStatus.INVALID_ARGUMENT, exception.getStatus());
            assertEquals("Password must have at least 1 special character", exception.getMessage());
        }

        @Test
        @DisplayName("Should accept password with uppercase, digit and special")
        void shouldAcceptPasswordWithUppercaseDigitAndSpecial() {
            String password = "Password123!";
            when(props.requireUpper()).thenReturn(true);
            when(props.requireDigit()).thenReturn(true);
            when(props.requireSpecial()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should accept password with uppercase and digit")
        void shouldAcceptPasswordWithUppercaseAndDigit() {
            String password = "Password123";
            when(props.requireUpper()).thenReturn(true);
            when(props.requireDigit()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should accept password when uppercase not required")
        void shouldAcceptPasswordWhenUppercaseNotRequired() {
            String password = "password123!";
            when(props.requireSpecial()).thenReturn(true);
            when(props.requireDigit()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }

        @Test
        @DisplayName("Should accept password when digit not required")
        void shouldAcceptPasswordWhenDigitNotRequired() {
            String password = "Password!";
            when(props.requireUpper()).thenReturn(true);
            when(props.requireSpecial()).thenReturn(true);

            assertDoesNotThrow(() -> validator.validate(password));
        }
    }
}