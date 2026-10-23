package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SqlStringValidationUtil Unit Tests")
class SqlStringValidationUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {"id", "project_id", "_internal", "Status2", "a"})
    void shouldAcceptPlainIdentifier(String identifier) {
        assertTrue(SqlStringValidationUtil.isValid(identifier));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "1st", "a-b", "a b", " id", "id ", "i.deleted_at", "public.issues.id",
            "\"id\"", "id;", "id--", "id OR 1=1", "задача"
    })
    void shouldRejectAnythingElse(String identifier) {
        assertFalse(SqlStringValidationUtil.isValid(identifier));
    }
}
