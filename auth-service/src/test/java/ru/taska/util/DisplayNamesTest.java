package ru.taska.util;

import org.junit.jupiter.api.Test;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import static org.assertj.core.api.Assertions.*;

class DisplayNamesTest {
    @Test void trimsUnicodeSpacesAndCountsCodePointsRatherThanUtf16Units() {
        assertThat(DisplayNames.normalize("  \u00a0Иван Иванов\u2003 ")).isEqualTo("Иван Иванов");
        String name = "😀".repeat(128);
        assertThat(DisplayNames.normalize(name)).isEqualTo(name);
    }
    @Test void preservesFormatCharactersInVisibleMultilingualNames() {
        assertThat(DisplayNames.normalize("نام\u200cخانوادگی")).isEqualTo("نام\u200cخانوادگی");
        assertThat(DisplayNames.normalize("👩\u200d💻")).isEqualTo("👩\u200d💻");
    }
    @Test void rejectsEmptyOverlongControlsAndLineBreaksWithStableReason() {
        for (String value : new String[]{null, "", " \u00a0 ", "😀".repeat(129), "a\nb", "\tName", "a\u0000b", "a\u0085b", "a\u2028b", "a\u2029b", "\ud800", "\u200b", "\ufeff", "\u2060", " \u200b\u00a0\ufeff\u2060 "}) {
            assertThatThrownBy(() -> DisplayNames.normalize(value)).isInstanceOfSatisfying(DomainException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                assertThat(error.getDetails()).containsEntry("reason", "DISPLAY_NAME_INVALID");
            });
        }
    }
}
