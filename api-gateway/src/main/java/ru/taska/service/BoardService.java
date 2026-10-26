package ru.taska.service;

import reactor.core.publisher.Mono;

import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardColumnPageResponseDto;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssuePriorityDto;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface BoardService {

    Mono<BoardResponseDto> getBoard(
            UUID projectId,
            String issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            IssuePriorityDto priority,
            String query,
            Integer pageSizePerColumn,
            GatewayContext context
    );

    Mono<BoardColumnPageResponseDto> listBoardColumnIssues(
            UUID projectId,
            String statusKey,
            OffsetDateTime afterCreatedAt,
            UUID afterId,
            String issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            IssuePriorityDto priority,
            String query,
            Integer pageSize,
            GatewayContext context);
}
