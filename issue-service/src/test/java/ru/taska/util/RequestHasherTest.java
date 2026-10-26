package ru.taska.util;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.IssuePriority;

class RequestHasherTest {
  private static final UUID PROJECT = UUID.randomUUID(), ACTOR = UUID.randomUUID();

  @Test
  void framingSeparatesUserTextContainingLegacyDelimiter() {
    assertThat(
            RequestHasher.hashIssueCreateRequest(
                PROJECT, IssueType.TASK, "a\u001fb", "c", IssuePriority.MEDIUM, ACTOR))
        .isEqualTo(
            RequestHasher.hashIssueCreateRequest(
                PROJECT, IssueType.TASK, "a", "b\u001fc", IssuePriority.MEDIUM, ACTOR));
    assertThat(hash("a\u001fb", "c", null)).isNotEqualTo(hash("a", "b\u001fc", null));
  }

  @Test
  void canonicalPersistedValuesMakeNumericallyEquivalentRetriesEqual() {
    assertThat(hash("summary", null, new BigDecimal("3.0")))
        .isEqualTo(hash("summary", "", new BigDecimal("3.00")));
  }

  @Test
  void absentStatusAndExplicitLegacyTodoShareOneCanonicalHash() {
    assertThat(
            RequestHasher.hashIssueCreateRequest(
                PROJECT,
                IssueType.TASK,
                "summary",
                "",
                IssuePriority.MEDIUM,
                ACTOR,
                new BigDecimal("3.00"),
                null,
                null,
                60,
                30,
                null))
        .isEqualTo(
            RequestHasher.hashIssueCreateRequest(
                PROJECT,
                IssueType.TASK,
                "summary",
                "",
                IssuePriority.MEDIUM,
                ACTOR,
                new BigDecimal("3.0"),
                null,
                null,
                60,
                30,
                "TODO"));
  }

  private String hash(String summary, String description, BigDecimal story) {
    return RequestHasher.hashIssueCreateRequest(
        PROJECT,
        IssueType.TASK,
        summary,
        description,
        IssuePriority.MEDIUM,
        ACTOR,
        story,
        null,
        null,
        null,
        null,
        "TODO");
  }
}
