package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssueTypeDto;

import java.util.UUID;

public interface BoardService {

    Mono<BoardResponseDto> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            IssuePriorityDto priority,
            String query,
            GatewayContext context
    );
}
