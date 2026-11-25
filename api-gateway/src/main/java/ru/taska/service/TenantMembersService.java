package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.MembershipPageDto;

import java.util.UUID;

public interface TenantMembersService {
    Mono<MembershipPageDto> list(
            UUID tenantId,
            String cursor,
            int limit,
            GatewayContext context
    );
}
