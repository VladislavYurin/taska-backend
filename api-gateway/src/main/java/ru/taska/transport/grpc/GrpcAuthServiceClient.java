package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequestBody;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.LoginRequestDto;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.PasswordByTokenRequestDto;
import ru.taska.domain.dto.RefreshRequestDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.mapper.AuthMapper;

import java.util.concurrent.TimeUnit;

/**
 * Реактивный gRPC-клиент для взаимодействия с микросервисом аутентификации (AuthService).
 * <p>
 * Класс инкапсулирует вызовы удаленных процедур через {@link ReactorAuthServiceGrpc.ReactorAuthServiceStub},
 * управляет динамическими таймаутами (Deadlines) для каждого запроса и координирует маппинг
 * данных между REST DTO и gRPC Protobuf моделями.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAuthServiceClient {

    private final ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub;
    private final AuthMapper authMapper;
    private final GrpcClientProperties properties;

    /**
     * Создает копию gRPC-стаба с динамически настроенным временем ожидания (Deadline).
     * <p>
     * Значение таймаута извлекается из конфигурационных свойств приложения для AuthService.
     *
     * @return {@link ReactorAuthServiceGrpc.ReactorAuthServiceStub} с примененным таймаутом выполнения
     */
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub dynamicStub() {
        return authServiceStub.withDeadlineAfter(properties.authService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Проверяет валидность Access Token (JWT) на стороне сервиса аутентификации.
     * <p>
     * Используется на этапе построения защищенного контекста в инфраструктуре фильтров Gateway.
     *
     * @param requestId уникальный идентификатор сквозной трассировки запроса
     * @param nodeId    идентификатор текущего узла API Gateway
     * @param accessToken строковое значение проверяемого токена доступа
     * @return {@link Mono} с ответом {@link ValidateAccessTokenResponse}, содержащим контекст пользователя
     */
    public Mono<ValidateAccessTokenResponse> validateAccessToken(String requestId, String nodeId, String accessToken) {

        log.info("[{}] Calling validateAccessToken", requestId);

        ValidateAccessTokenRequest request = ValidateAccessTokenRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(ValidateAccessTokenRequestBody.newBuilder()
                        .setAccessToken(accessToken)
                        .build())
                .build();

        return dynamicStub().validateAccessToken(request);
    }

    /**
     * Выполняет процедуру аутентификации пользователя (Login).
     *
     * @param request поток с DTO запроса авторизации (email, password)
     * @param context текущий контекст окружения запроса на Gateway
     * @return {@link Mono} с заполненным REST DTO {@link LoginResponseDto} (токены и время их жизни)
     */
    public Mono<LoginResponseDto> login(Mono<LoginRequestDto> request, GatewayContext context) {
        log.info("[{}] Calling login", context.requestId());
        return request.flatMap(requestDto ->
                dynamicStub().login(authMapper.toLoginGrpcRequest(requestDto, context))
        ).map(authMapper::toLoginRestResponse);
    }

    /**
     * Выполняет ротацию сессии по Refresh токену (Refresh).
     *
     * @param request поток с DTO, содержащим текущий токен обновления
     * @param context текущий контекст окружения запроса на Gateway
     * @return {@link Mono} с REST DTO {@link RefreshResponseDto}, содержащим новую пару токенов
     */
    public Mono<RefreshResponseDto> refresh(Mono<RefreshRequestDto> request, GatewayContext context) {
        log.info("[{}] Calling refresh", context.requestId());
        return request.flatMap(requestDto ->
                dynamicStub().refresh(authMapper.toRefreshGrpcRequest(requestDto, context))
        ).map(authMapper::toRefreshRestResponse);
    }

    /**
     * Активирует учетную запись и устанавливает пароль пользователя по инвайт-токену.
     *
     * @param request поток с DTO, содержащим инвайт-токен и новый пароль
     * @param context текущий контекст окружения запроса на Gateway
     * @return {@link Mono<Void>}, завершающийся успешно при успешной обработке на стороне gRPC-сервиса
     */
    public Mono<Void> setPasswordByToken(Mono<PasswordByTokenRequestDto> request, GatewayContext context) {
        log.info("[{}] Calling setPasswordByToken", context.requestId());
        return request.flatMap(requestDto ->
                        dynamicStub().setPasswordByToken(authMapper.toPasswordByTokenGrpcRequest(requestDto, context)))
                .then();
    }
}
