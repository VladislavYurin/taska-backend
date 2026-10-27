package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import lombok.Getter;
import nullable.NullableField;

/**
 * PATCH body of a registry entry. Omitted fields stay unchanged. Only {@code icon} can be cleared: Jackson calls
 * its setter for an explicit {@code null} too, so {@link NullableField} keeps "absent" apart from "remove the icon".
 * Explicit null is rejected for every other field, because those values cannot be cleared.
 */
@Getter(onMethod_ = @JsonIgnore)
public class PatchProjectIssueTypeRequestDto {
    private String name;
    private String color;
    private String shape;
    private NullableField<String> icon = NullableField.absent();
    private Integer sortOrder;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String name) { this.name = name; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setColor(String color) { this.color = color; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setShape(String shape) { this.shape = shape; }

    @JsonSetter("icon")
    public void setIcon(String icon) { this.icon = NullableField.of(icon); }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(BigDecimal sortOrder) { this.sortOrder = sortOrder.intValueExact(); }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown or immutable field: " + key);
    }
}
