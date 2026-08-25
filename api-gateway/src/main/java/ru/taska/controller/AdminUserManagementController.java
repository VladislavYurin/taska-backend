package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AdminUserManagementApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.BlockUserRequestDto;
import ru.taska.domain.dto.UnblockUserRequestDto;
import ru.taska.domain.dto.UserStatusResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcAdminServiceClient;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AdminUserManagementController implements AdminUserManagementApi {

    private final GatewayRequestExecutor executor;
    private final GrpcAdminServiceClient adminServiceClient;

    public Mono<ResponseEntity<UserStatusResponseDto>> blockUser(
            UUID userId,
            Mono<BlockUserRequestDto> reasonDto,
            ServerWebExchange exchange
    ) {
        return reasonDto.flatMap(dto ->
                executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                                adminServiceClient.blockUser(userId, dto.getReason(), context))
                        .map(ResponseEntity::ok)
        );
    }

    public Mono<ResponseEntity<UserStatusResponseDto>> unblockUser(
            UUID userId,
            Mono<UnblockUserRequestDto> reasonDto,
            ServerWebExchange exchange
    ) {
        return reasonDto.flatMap(dto ->
                executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                                adminServiceClient.unblockUser(userId, dto.getReason(), context))
                        .map(ResponseEntity::ok)
        );
    }
}

