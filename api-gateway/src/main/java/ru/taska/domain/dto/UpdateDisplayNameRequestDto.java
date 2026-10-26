package ru.taska.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Validation uses Unicode code points in DisplayNames, not Bean Validation UTF-16 length. */
@Data
@NoArgsConstructor
public class UpdateDisplayNameRequestDto {
    private String displayName;
}
