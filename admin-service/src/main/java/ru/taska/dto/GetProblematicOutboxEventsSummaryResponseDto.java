package ru.taska.dto;

import java.util.List;

public record GetProblematicOutboxEventsSummaryResponseDto(
        List<ProblematicOutboxEventResponseDto> events,
        List<ProblematicEventCountDto> counts,
        boolean notAllShown
) {
}
