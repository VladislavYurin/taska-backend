package ru.taska.util;

public final class DataMaskingHelper {

    private static final String NULL_OR_EMPTY = "[null or empty]";
    private static final String INVALID = "[invalid]";

    private DataMaskingHelper() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return NULL_OR_EMPTY;
        }

        String[] parts = email.split("@");

        // invalid email without @ or local/domain part
        if (parts.length < 2 || parts[1].isBlank() || parts[0].isBlank()) {
            return INVALID;
        }

        String domain = "@" + parts[1];
        String local = parts[0];

        if (local.length() == 1) {
            return "*" + domain;
        }

        if (local.length() == 2) {
            return local.charAt(0) + "*" + domain;
        }

        return local.charAt(0) + "*".repeat(local.length() - 2)
                + local.charAt(local.length() - 1) + domain;
    }

    public static String maskJwt(String jwt) {

        if (jwt == null || jwt.isBlank()) {
            return NULL_OR_EMPTY;
        }

        if (jwt.length() < 8) {
            return INVALID;
        }

        return jwt.substring(0, 8) + "...";
    }
}
