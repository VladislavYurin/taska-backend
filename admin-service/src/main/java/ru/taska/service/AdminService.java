package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;

public interface AdminService {

    Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto);

    Mono<GetTableRowByIdResponseDto> getTableRowById(GetTableRowByIdRequestDto requestDto);
}
