package ru.taska.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import nullable.NullableField;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.util.StoryPointsNormalizer;

/**
 * Изменения задачи из PATCH-запроса.
 *
 * {@code summary}/{@code priority} — NOT NULL колонки, поэтому они {@link Optional}:
 * {@link Optional#empty()} — не менять, {@link Optional} со значением — установить это значение;
 * очистить их через PATCH нельзя.
 * <p>Остальные поля допускают NULL в БД, поэтому они {@link NullableField}: отсутствие
 * {@link NullableField#present()} — не менять поле, {@code present=true, value=null} —
 * очистить поле, {@code present=true, value!=null} — установить в поле новое значение.</p>
 */
public record IssuePatch(
        Optional<String> summary,
        Optional<IssuePriority> priority,
        NullableField<String> description,
        NullableField<UUID> assigneeId,
        NullableField<BigDecimal> storyPoints,
        NullableField<LocalDate> startDate,
        NullableField<LocalDate> dueDate,
        NullableField<Integer> originalEstimateMinutes,
        NullableField<Integer> remainingEstimateMinutes,
        Optional<ru.taska.domain.IssueType> issueType
) {

    public IssuePatch(Optional<String> summary, Optional<IssuePriority> priority, NullableField<String> description,
                      NullableField<UUID> assigneeId, NullableField<BigDecimal> storyPoints,
                      NullableField<LocalDate> startDate, NullableField<LocalDate> dueDate,
                      NullableField<Integer> originalEstimateMinutes, NullableField<Integer> remainingEstimateMinutes) {
        this(summary, priority, description, assigneeId, storyPoints, startDate, dueDate,
                originalEstimateMinutes, remainingEstimateMinutes, Optional.empty());
    }

    /**
     * Возвращает копию {@code issue} с применёнными изменениями. Сам {@code issue} не меняется.
     */
    public Issue applyTo(Issue issue) {
        return issue.toBuilder()
                .issueType(issueType.orElse(issue.getIssueType()))
                .summary(summary.orElse(issue.getSummary()))
                .priority(priority.orElse(issue.getPriority()))
                .description(description.orElse(issue.getDescription()))
                .assigneeId(assigneeId.orElse(issue.getAssigneeId()))
                .storyPoints(StoryPointsNormalizer.normalize(storyPoints.orElse(issue.getStoryPoints())))
                .startDate(startDate.orElse(issue.getStartDate()))
                .dueDate(dueDate.orElse(issue.getDueDate()))
                .originalEstimateMinutes(originalEstimateMinutes.orElse(issue.getOriginalEstimateMinutes()))
                .remainingEstimateMinutes(remainingEstimateMinutes.orElse(issue.getRemainingEstimateMinutes()))
                .build();
    }
}
