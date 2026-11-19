package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.TenantMembersApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.MembershipPageDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.TenantMembersService;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class TenantMembersController implements TenantMembersApi {

    private final GatewayRequestExecutor executor;
    private final TenantMembersService members;

    @Override
    public Mono<ResponseEntity<MembershipPageDto>> listTenantMembers(
            UUID tenantId,
            String cursor,
            Integer limit,
            ServerWebExchange exchange
    ) {
        exchange.getResponse()
                .getHeaders()
                .setCacheControl("private, no-store");
        return executor.execute(
                exchange,
                EndpointSecurity.PROTECTED,
                context -> members.list(
                                tenantId,
                                cursor,
                                limit == null ? 50 : limit,
                                context
                        )
                        .map(page -> ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore().cachePrivate())
                                .body(page))
        );
    }
}