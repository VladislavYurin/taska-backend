package ru.taska.filter;

import java.util.regex.Pattern;

/**
 * Проверка имён колонок, которые подставляются в SQL
 * без экранирования. Защищает от SQL-инъекций.
 * Разрешено только простое имя колонки: точка (префикс таблицы или схемы) не допускается.
 */
final class SqlStringValidationUtil {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlStringValidationUtil() {
    }

    static boolean isValid(String identifier) {
        return identifier != null && IDENTIFIER.matcher(identifier).matches();
    }
}
