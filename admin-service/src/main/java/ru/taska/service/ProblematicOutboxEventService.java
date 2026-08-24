package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;

public interface ProblematicOutboxEventService {

    Mono<GetProblematicOutboxEventsSummaryResponseDto> getProblematicOutboxEventsSummary(
            String serviceKey, String requestId, String nodeId);
}
