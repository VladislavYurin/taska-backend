package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

class IssueMapperTest {

    private final IssueMapper issueMapper = new IssueMapper(new ObjectMapper());

    @Test
    @DisplayName("get/list issue возвращает планировочные поля")
    void getListIssueReturnsPlanningFields() {
        Instant start = Instant.parse("2026-07-24T12:00:00Z");
        Instant due = start.plusSeconds(7200);

        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProjectId(UUID.randomUUID());
        issue.setIssueNumber(1);
        issue.setIssueKey("TSK-1");
        issue.setSummary("Тестовая задача");
        issue.setStatusKey("TODO");
        issue.setReporterId(UUID.randomUUID());
        issue.setVersion(1);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());

        issue.setIssueType(IssueType.STORY);
        issue.setPriority(IssuePriority.MEDIUM);
        issue.setStoryPoints(8.5);
        issue.setStartDate(start);
        issue.setDueDate(due);
        issue.setOriginalEstimateMinutes(120L);

        IssueResponse response = issueMapper.toIssueProto(issue);
        Assertions.assertThat(response.getStoryPoints()).isEqualTo(8.5);
        Assertions.assertThat(response.getStartDate().getSeconds()).isEqualTo(start.getEpochSecond());
        Assertions.assertThat(response.getDueDate().getSeconds()).isEqualTo(due.getEpochSecond());
        Assertions.assertThat(response.getOriginalEstimateMinutes()).isEqualTo(120L);

        IssueShortResponse shortResponse = issueMapper.toIssueShortProto(issue);
        Assertions.assertThat(shortResponse.getStoryPoints()).isEqualTo(8.5);
        Assertions.assertThat(shortResponse.getStartDate().getSeconds()).isEqualTo(start.getEpochSecond());
        Assertions.assertThat(shortResponse.getDueDate().getSeconds()).isEqualTo(due.getEpochSecond());
        Assertions.assertThat(shortResponse.getOriginalEstimateMinutes()).isEqualTo(120L);
    }
}