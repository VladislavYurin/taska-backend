package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;

public interface AdminReadonlyService {

    Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto, String requestId, String nodeId);

    Mono<GetTableRowByIdResponseDto> getTableRowById(GetTableRowByIdRequestDto requestDto, String requestId, String nodeId);
}
