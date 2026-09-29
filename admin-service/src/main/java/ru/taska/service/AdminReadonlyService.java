package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.PageResult;
import ru.taska.dto.*;


public interface AdminReadonlyService {

    Mono<ListTableRowsResponseDto> listTableRows(ListTableRowsRequestDto requestDto, String requestId, String nodeId);

    Mono<GetTableRowByIdResponseDto> getTableRowById(GetTableRowByIdRequestDto requestDto, String requestId, String nodeId);

    Mono<PageResult<AuditEntriesResponseDto>> listAuditEntries(FilterAuditDTO filterAuditDTO, Integer page, Integer pageSize);
}
