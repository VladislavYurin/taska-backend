package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.PageResult;
import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.GetTableRowByIdResponseDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.dto.FilterAuditDTO;



public interface AdminReadonlyService {

    Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto, String requestId, String nodeId);

    Mono<GetTableRowByIdResponseDto> getTableRowById(GetTableRowByIdRequestDto requestDto, String requestId, String nodeId);

    Mono<PageResult<AuditEntriesResponseDto>> listAuditEntries(FilterAuditDTO filterAuditDTO, Integer page, Integer pageSize);
}
