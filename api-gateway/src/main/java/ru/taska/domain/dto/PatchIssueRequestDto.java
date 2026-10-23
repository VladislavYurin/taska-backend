package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import nullable.NullableField;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Тело запроса PATCH /api/v1/issues/{issueId} (JSON Merge Patch).
 *
 * <p>Схема описана в openapi.yml, но класс не генерируется: он подключён через {@code schemaMappings},
 * потому что сгенерированный DTO не отличает отсутствующее поле от явно переданного {@code null}.</p>
 *
 * <p>Каждое поле хранится как {@link NullableField}: Jackson вызывает setter только для ключей,
 * которые есть в JSON (в том числе со значением {@code null}), поэтому поле, не пришедшее в теле,
 * остаётся {@link NullableField#absent()}.</p>
 *
 * <p>Оценки времени хранятся как {@link BigDecimal}, а не {@link Integer}: иначе Jackson молча отбросит
 * дробную часть (90.9 → 90). Целочисленность проверяет маппер.</p>
 *
 * <p>Класс предназначен только для десериализации: getters (Lombok) помечены {@link JsonIgnore}.</p>
 */
@Getter(onMethod_ = @JsonIgnore)
public class PatchIssueRequestDto {

    private NullableField<String> summary = NullableField.absent();
    private NullableField<String> description = NullableField.absent();
    private NullableField<IssuePriorityDto> priority = NullableField.absent();
    private NullableField<String> assigneeId = NullableField.absent();
    private NullableField<Double> storyPoints = NullableField.absent();
    private NullableField<LocalDate> startDate = NullableField.absent();
    private NullableField<LocalDate> dueDate = NullableField.absent();
    private NullableField<BigDecimal> originalEstimateMinutes = NullableField.absent();
    private NullableField<BigDecimal> remainingEstimateMinutes = NullableField.absent();

    @JsonProperty("summary")
    public void setSummary(String summary) {
        this.summary = NullableField.of(summary);
    }

    @JsonProperty("description")
    public void setDescription(String description) {
        this.description = NullableField.of(description);
    }

    @JsonProperty("priority")
    public void setPriority(IssuePriorityDto priority) {
        this.priority = NullableField.of(priority);
    }

    @JsonProperty("assigneeId")
    public void setAssigneeId(String assigneeId) {
        this.assigneeId = NullableField.of(assigneeId);
    }

    @JsonProperty("storyPoints")
    public void setStoryPoints(Double storyPoints) {
        this.storyPoints = NullableField.of(storyPoints);
    }

    @JsonProperty("startDate")
    public void setStartDate(LocalDate startDate) {
        this.startDate = NullableField.of(startDate);
    }

    @JsonProperty("dueDate")
    public void setDueDate(LocalDate dueDate) {
        this.dueDate = NullableField.of(dueDate);
    }

    @JsonProperty("originalEstimateMinutes")
    public void setOriginalEstimateMinutes(BigDecimal originalEstimateMinutes) {
        this.originalEstimateMinutes = NullableField.of(originalEstimateMinutes);
    }

    @JsonProperty("remainingEstimateMinutes")
    public void setRemainingEstimateMinutes(BigDecimal remainingEstimateMinutes) {
        this.remainingEstimateMinutes = NullableField.of(remainingEstimateMinutes);
    }
}
