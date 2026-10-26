package ru.taska.util;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import java.util.Map;

/** Shared display-name contract for profile, invitations and registration. */
public final class DisplayNames {
    private DisplayNames() { }
    public static String normalize(String raw) {
        if (raw == null || raw.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029
                || Character.getType(cp) == Character.SURROGATE)) {
            throw invalid();
        }
        int start = 0, end = raw.length();
        while (start < end && space(raw.codePointAt(start))) start += Character.charCount(raw.codePointAt(start));
        while (end > start && space(raw.codePointBefore(end))) end -= Character.charCount(raw.codePointBefore(end));
        String value = raw.substring(start, end);
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > 128) throw invalid();
        return value;
    }
    private static boolean space(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
    private static DomainException invalid() {
        return new DomainException(DomainStatus.INVALID_ARGUMENT,
                "Display name must contain 1 to 128 characters and no control characters or line breaks",
                Map.of("reason", "DISPLAY_NAME_INVALID"));
    }
}
