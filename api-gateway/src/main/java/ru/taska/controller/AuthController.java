package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AuthApi;
import ru.taska.domain.dto.LoginRequestDto;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.PasswordByTokenRequestDto;
import ru.taska.domain.dto.RefreshRequestDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.UserService;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import static ru.taska.domain.EndpointSecurity.PROTECTED;
import static ru.taska.domain.EndpointSecurity.PUBLIC;

/**
 * REST-контроллер API Gateway для управления процессами аутентификации и авторизации.
 * <p>
 * Обеспечивает проксирование входящих REST-запросов во внутреннюю систему аутентификации через gRPC,
 * оборачивая вызовы в логику построения сквозного контекста выполнения (трассировка, метаданные узла).
 * Реализует сгенерированный интерфейс {@link AuthApi}.
 */
@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final GrpcAuthServiceClient grpcAuthServiceClient;
    private final UserService userService;
    private final GatewayRequestExecutor executor;

    /**
     * Выполняет аутентификацию пользователя по адресу электронной почты и паролю.
     * <p>
     * Эндпоинт является публичным (PUBLIC). При успешной проверке учётных данных
     * возвращает новую пару JWT-токенов (access и refresh) и время их жизни.
     *
     * @param loginRequestDto входящий поток с учётными данными пользователя (email, password)
     * @param exchange        текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK) и токенами внутри {@link LoginResponseDto}
     */
    @Override
    public Mono<ResponseEntity<LoginResponseDto>> login(Mono<LoginRequestDto> loginRequestDto, ServerWebExchange exchange) {
        return executor.execute(exchange, PUBLIC, context ->
                grpcAuthServiceClient.login(loginRequestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Выполняет ротацию токенов на основании предоставленного Refresh-токена.
     * <p>
     * Эндпоинт является публичным (PUBLIC). Старый токен инвалидируется, а клиенту
     * возвращается полностью новая пара токенов доступа.
     *
     * @param refreshRequestDto входящий поток с действующим токеном обновления (refreshToken)
     * @param exchange          текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK) и новыми токенами внутри {@link RefreshResponseDto}
     */
    @Override
    public Mono<ResponseEntity<RefreshResponseDto>> refresh(Mono<RefreshRequestDto> refreshRequestDto, ServerWebExchange exchange) {
        return executor.execute(exchange, PUBLIC, context ->
                grpcAuthServiceClient.refresh(refreshRequestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Принимает приглашение в систему и устанавливает постоянный пароль учётной записи по инвайт-токену.
     * <p>
     * Эндпоинт является публичным (PUBLIC). Автоматический вход в систему после активации не производится.
     *
     * @param passwordByTokenRequestDto входящий поток с инвайт-токеном и новым паролем
     * @param exchange                  текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий пустой {@link ResponseEntity} со статусом 204 (No Content) в случае успеха
     */
    @Override
    public Mono<ResponseEntity<Void>> setPasswordByToken(Mono<PasswordByTokenRequestDto> passwordByTokenRequestDto, ServerWebExchange exchange) {
        return executor.execute(exchange, PUBLIC, context ->
                grpcAuthServiceClient.setPasswordByToken(passwordByTokenRequestDto, context)
                        .then(Mono.just(ResponseEntity.noContent().build()))
        );
    }

    /**
     * Возвращает профиль и контекст безопасности текущего аутентифицированного пользователя.
     * <p>
     * Эндпоинт является защищённым (PROTECTED). Перед выполнением метода фабрика контекстов автоматически
     * извлекает заголовок Authorization, проверяет Bearer-токен через gRPC и маппит данные пользователя,
     * очищая системные прото-префиксы у статусов.
     *
     * @param exchange текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK) и данными профиля внутри {@link ValidateAccessTokenResponseDto}
     */
    @Override
    public Mono<ResponseEntity<ValidateAccessTokenResponseDto>> getMyInfo(ServerWebExchange exchange) {
        return executor.execute(exchange, PROTECTED, context ->
                userService.getMyInfo(context)
                        .map(ResponseEntity::ok)
        );
    }
}
