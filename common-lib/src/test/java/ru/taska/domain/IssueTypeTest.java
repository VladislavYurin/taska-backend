package ru.taska.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class IssueTypeTest {
    @Test
    void openKeyRoundTripsAsJsonStringWithoutChangingBuiltinValues() {
        var mapper = new ObjectMapper();
        var request = IssueType.valueOf("REQUEST");
        assertThat(mapper.writeValueAsString(request)).isEqualTo("\"REQUEST\"");
        assertThat(mapper.readValue("\"REQUEST\"", IssueType.class)).isEqualTo(request);
        assertThat(IssueType.valueOf("TASK")).isSameAs(IssueType.TASK);
        assertThat(IssueType.valueOf("BUG")).isSameAs(IssueType.BUG);
        assertThat(IssueType.valueOf("STORY")).isSameAs(IssueType.STORY);
    }

    @Test
    void rejectsInvalidAndOversizedKeysWithoutNormalizingThem() {
        for (String key : new String[]{"", "X", "request", " REQUEST", "A-B", "A".repeat(33)}) {
            assertThatThrownBy(() -> IssueType.valueOf(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(IssueType.valueOf("A_" + "1".repeat(30)).name()).hasSize(32);
    }
}
