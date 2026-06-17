package ru.taska.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DataMaskingHelper Unit Tests")
public class DataMaskingHelperTest {

    private static final String NULL_MARKER = "[null or empty]";
    private static final String INVALID_MARKER = "[invalid]";

    @Nested
    @DisplayName("Mask email tests")
    class MaskEmailTests {

        @Test
        void shouldReturnNullMarker_whenNullInput() {
            assertEquals(DataMaskingHelperTest.NULL_MARKER,
                    DataMaskingHelper.maskEmail(null));
        }

        @Test
        void shouldReturnNullMarker_whenEmptyInput() {
            assertEquals(NULL_MARKER,
                    DataMaskingHelper.maskEmail(""));
        }

        @Test
        void shouldReturnInvalidMarker_whenNoAtInput() {
            assertEquals(INVALID_MARKER,
                    DataMaskingHelper.maskEmail("some-text"));
        }

        @Test
        void shouldReturnInvalidMarker_whenNoLocalPartInput() {
            assertEquals(INVALID_MARKER,
                    DataMaskingHelper.maskEmail("@domain.com"));
        }

        @Test
        void shouldReturnInvalidMarker_whenNoDomainPartInput() {
            assertEquals(INVALID_MARKER,
                    DataMaskingHelper.maskEmail("local@"));
        }

        @Test
        void shouldMaskSingleCharacterLocalPart() {
            assertEquals("*@domain.com",
                    DataMaskingHelper.maskEmail("a@domain.com"));
        }

        @Test
        void shouldMaskTwoCharactersLocalPart() {
            assertEquals("a*@domain.com",
                    DataMaskingHelper.maskEmail("ab@domain.com"));
        }

        @Test
        void shouldMaskDefaultCasesCharactersLocalPart() {
            assertEquals("a*c@domain.com",
                    DataMaskingHelper.maskEmail("abc@domain.com"));

            assertEquals("i**n@domain.com",
                    DataMaskingHelper.maskEmail("ivan@domain.com"));

            assertEquals("i*********v@domain.com",
                    DataMaskingHelper.maskEmail("ivan.petrov@domain.com"));
        }
    }

    @Nested
    @DisplayName("Mask JWT tests")
    class MaskJwtTests {

        @Test
        void shouldReturnNullMarker_whenNullInput() {
            assertEquals(DataMaskingHelperTest.NULL_MARKER,
                    DataMaskingHelper.maskJwt(null));
        }

        @Test
        void shouldReturnNullMarker_whenEmptyInput() {
            assertEquals(NULL_MARKER,
                    DataMaskingHelper.maskJwt(""));
        }

        @Test
        void shouldReturnInvalidMarker_whenInputLengthLessEight() {
            assertEquals(INVALID_MARKER,
                    DataMaskingHelper.maskJwt("1234567"));
        }

        @Test
        void shouldReturnFingerPrint_whenCorrectInput() {
            assertEquals("eyJhbGci...",
                    DataMaskingHelper.maskJwt("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        }
    }
}
