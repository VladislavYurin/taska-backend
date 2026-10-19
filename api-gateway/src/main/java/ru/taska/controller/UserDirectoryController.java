package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.UserDirectoryApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.InvitationRequestDto;
import ru.taska.domain.dto.InvitationResponseDto;
import ru.taska.domain.dto.UserDirectoryResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcUserDirectoryClient;

@RestController
@RequiredArgsConstructor
public class UserDirectoryController implements UserDirectoryApi {
    private final GatewayRequestExecutor executor;
    private final GrpcUserDirectoryClient directory;

    @Override
    public Mono<ResponseEntity<InvitationResponseDto>> inviteUser(Mono<InvitationRequestDto> body, ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED,
                context -> body.flatMap(value -> directory.invite(value, context))
                        .map(result -> ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                                .cacheControl(CacheControl.noStore()).body(result.body())));
    }

    @Override
    public Mono<ResponseEntity<UserDirectoryResponseDto>> searchUsers(String query, Integer page, Integer pageSize,
                                                                     ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,
                context -> directory.search(query, page, pageSize, context)
                        .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)));
    }
}
