package ru.taska.service.worklog;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.api.issue.v1.WorklogResponse;
import ru.taska.domain.Worklog;
import ru.taska.mapper.WorklogMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@DisplayName("WorklogMapper Unit Tests")
class WorklogMapperTest {

    private final WorklogMapper worklogMapper = new WorklogMapper();

    private Worklog baseWorklog() {
        return Worklog.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .issueId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .projectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .authorUserId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
                .spentMinutes(30)
                .workDate(LocalDate.of(2026, 9, 13))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(1)
                .build();
    }

    @Test
    @DisplayName("toWorklogProto: должен корректно маппить version без NPE")
    void shouldMapVersionWithoutNpe() {
        Worklog worklog = baseWorklog();

        WorklogResponse response = worklogMapper.toWorklogProto(worklog);

        Assertions.assertThat(response.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("toWorklogProto: не должен устанавливать comment, если он null")
    void shouldNotSetCommentWhenNull() {
        Worklog worklog = baseWorklog().toBuilder().comment(null).build();

        WorklogResponse response = worklogMapper.toWorklogProto(worklog);

        Assertions.assertThat(response.hasComment()).isFalse();
        Assertions.assertThat(response.getComment()).isEmpty();
    }

    @Test
    @DisplayName("toWorklogProto: должен корректно маппить comment, если он задан")
    void shouldSetCommentWhenPresent() {
        Worklog worklog = baseWorklog().toBuilder().comment("worked on API").build();

        WorklogResponse response = worklogMapper.toWorklogProto(worklog);

        Assertions.assertThat(response.hasComment()).isTrue();
        Assertions.assertThat(response.getComment()).isEqualTo("worked on API");
    }

    @Test
    @DisplayName("toWorklogProto: должен корректно маппить базовые поля")
    void shouldMapBasicFields() {
        Worklog worklog = baseWorklog();

        WorklogResponse response = worklogMapper.toWorklogProto(worklog);

        Assertions.assertThat(response.getId()).isEqualTo(worklog.getId().toString());
        Assertions.assertThat(response.getIssueId()).isEqualTo(worklog.getIssueId().toString());
        Assertions.assertThat(response.getProjectId()).isEqualTo(worklog.getProjectId().toString());
        Assertions.assertThat(response.getAuthorUserId()).isEqualTo(worklog.getAuthorUserId().toString());
        Assertions.assertThat(response.getSpentMinutes()).isEqualTo(30);
        Assertions.assertThat(response.getWorkDate()).isEqualTo("2026-09-13");
    }

    @Test
    @DisplayName("toAddResponse: должен обернуть WorklogResponse в AddIssueWorklogResponse")
    void shouldWrapIntoAddResponse() {
        Worklog worklog = baseWorklog();

        AddIssueWorklogResponse response = worklogMapper.toAddResponse(worklog);

        Assertions.assertThat(response.getWorklog().getId()).isEqualTo(worklog.getId().toString());
    }

    @Test
    @DisplayName("toUpdateResponse: должен обернуть WorklogResponse в UpdateIssueWorklogResponse")
    void shouldWrapIntoUpdateResponse() {
        Worklog worklog = baseWorklog();

        UpdateIssueWorklogResponse response = worklogMapper.toUpdateResponse(worklog);

        Assertions.assertThat(response.getWorklog().getId()).isEqualTo(worklog.getId().toString());
    }

    @Test
    @DisplayName("toDeleteResponse: должен обернуть WorklogResponse в DeleteIssueWorklogResponse")
    void shouldWrapIntoDeleteResponse() {
        Worklog worklog = baseWorklog();

        DeleteIssueWorklogResponse response = worklogMapper.toDeleteResponse(worklog);

        Assertions.assertThat(response.getWorklog().getId()).isEqualTo(worklog.getId().toString());
    }

    @Test
    @DisplayName("toListResponse: должен смаппить список worklogs")
    void shouldMapListOfWorklogs() {
        Worklog first = baseWorklog();
        Worklog second = baseWorklog().toBuilder().id(UUID.randomUUID()).build();

        ListIssueWorklogsResponse response = worklogMapper.toListResponse(List.of(first, second));

        Assertions.assertThat(response.getWorklogsList()).hasSize(2);
    }

    @Test
    @DisplayName("toListResponse: должен вернуть пустой список, если входной список пуст")
    void shouldReturnEmptyListWhenInputEmpty() {
        ListIssueWorklogsResponse response = worklogMapper.toListResponse(List.of());

        Assertions.assertThat(response.getWorklogsList()).isEmpty();
    }
}