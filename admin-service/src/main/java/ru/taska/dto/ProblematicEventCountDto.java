package ru.taska.dto;

public record ProblematicEventCountDto(
        String serviceKey,
        long overdueNewCount,
        long stuckProcessingCount,
        long failedCount
) {
}
