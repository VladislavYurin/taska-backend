package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AdminUserManagementApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.BlockUserRequestDto;
import ru.taska.domain.dto.ResetLockoutRequestDto;
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

    @Override
    public Mono<ResponseEntity<UserStatusResponseDto>> blockUser(
            UUID userId,
            Mono<BlockUserRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.blockUser(userId, requestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    @Override
    public Mono<ResponseEntity<UserStatusResponseDto>> unblockUser(
            UUID userId,
            Mono<UnblockUserRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.unblockUser(userId, requestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * POST /api/v1/admin/users/{userId}/reset-lockout
     * Снимает блокировку с пользователя после его неудачных попыток входа
     */
    @Override
    public Mono<ResponseEntity<UserStatusResponseDto>> resetCredentialLockout(
            UUID userId,
            Mono<ResetLockoutRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient
                        .resetCredentialLockout(userId, requestDto, context)
                        .map(ResponseEntity::ok)
        );
    }
}

