package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardResponseDto;

import java.util.UUID;

public interface BoardService {

    Mono<BoardResponseDto> getBoard(
            UUID projectId,
            String issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            GatewayContext context
    );
}