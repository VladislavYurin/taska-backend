package ru.taska.service;

import ru.taska.domain.dto.MetaResponseDto;

public interface MetaService {

    MetaResponseDto getMeta();

    String getEtag();
}
