package ru.taska.domain.dto;

import java.time.LocalDate;

public record UpdateWorklogDto(
        Integer spentMinutes,
        LocalDate workDate,
        String comment
) {
}
