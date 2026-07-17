package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshRequestBody;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.SetPasswordByTokenRequestBody;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.Header;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.LoginRequestDto;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.PasswordByTokenRequestDto;
import ru.taska.domain.dto.RefreshRequestDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;

/**
 * Компонент-маппер для преобразования моделей данных слоя аутентификации.
 * <p>
 * Обеспечивает двустороннюю конвертацию между объектами REST API (DTO) и
 * объектами сетевого gRPC-транспорта (Protobuf Messages), а также маппинг
 * внутренних доменных контекстов безопасности API Gateway.
 */
@Component
public class AuthMapper {

    /**
     * Преобразует REST DTO аутентификации и контекст шлюза в gRPC запрос LoginRequest.
     *
     * @param source  исходные данные авторизации от клиента (email, password)
     * @param context текущий контекст сквозной трассировки и идентификации узла шлюза
     * @return сформированный gRPC-объект {@link LoginRequest} с заполненным заголовком и телом
     */
    public LoginRequest toLoginGrpcRequest(LoginRequestDto source, GatewayContext context) {
        return LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(source.getEmail())
                        .setPassword(source.getPassword())
                        .build())
                .build();
    }

    /**
     * Преобразует ответ gRPC сервиса аутентификации в REST DTO формата ответа.
     *
     * @param source полученный от gRPC-сервиса объект {@link LoginResponse} с токенами
     * @return заполненный REST DTO объект {@link LoginResponseDto} для отправки клиенту
     */
    public LoginResponseDto toLoginRestResponse(LoginResponse source) {
        LoginResponseDto dto = new LoginResponseDto();
        dto.setAccessToken(source.getAccessToken());
        dto.setRefreshToken(source.getRefreshToken());
        dto.setExpiresIn(source.getExpiresIn());
        return dto;
    }

    /**
     * Преобразует REST DTO запроса ротации токенов и контекст шлюза в gRPC запрос RefreshRequest.
     *
     * @param source  исходные данные с токеном обновления (refreshToken)
     * @param context текущий контекст сквозной трассировки и идентификации узла шлюза
     * @return сформированный gRPC-объект {@link RefreshRequest} с заполненным заголовком и телом
     */
    public RefreshRequest toRefreshGrpcRequest(RefreshRequestDto source, GatewayContext context) {
        return RefreshRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken(source.getRefreshToken())
                        .build())
                .build();
    }

    /**
     * Преобразует gRPC ответ ротации сессии в REST DTO формата ответа.
     *
     * @param source полученный от gRPC-сервиса объект {@link RefreshResponse} с новой парой токенов
     * @return заполненный REST DTO объект {@link RefreshResponseDto} для отправки клиенту
     */
    public RefreshResponseDto toRefreshRestResponse(RefreshResponse source) {
        RefreshResponseDto dto = new RefreshResponseDto();
        dto.setAccessToken(source.getAccessToken());
        dto.setRefreshToken(source.getRefreshToken());
        dto.setExpiresIn(source.getExpiresIn());
        return dto;
    }

    /**
     * Преобразует REST DTO установки пароля по инвайту и контекст шлюза в gRPC запрос SetPasswordByTokenRequest.
     *
     * @param source  исходные данные принятия приглашения (токен инвайта, новый пароль)
     * @param context текущий контекст сквозной трассировки и идентификации узла шлюза
     * @return сформированный gRPC-объект {@link SetPasswordByTokenRequest} с заполненным заголовком и телом
     */
    public SetPasswordByTokenRequest toPasswordByTokenGrpcRequest(PasswordByTokenRequestDto source, GatewayContext context) {
        return SetPasswordByTokenRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(SetPasswordByTokenRequestBody.newBuilder()
                        .setToken(source.getToken())
                        .setNewPassword(source.getNewPassword())
                        .build())
                .build();
    }

    /**
     * Преобразует внутренний контекст пользователя шлюза в REST DTO ответа профиля информации.
     * Поле статуса приводится к строковому представлению без gRPC-префиксов системы.
     *
     * @param userContext верифицированный внутренний контекст пользователя {@link GatewayUserContext}
     * @return заполненный REST DTO объект {@link ValidateAccessTokenResponseDto} для эндпоинта информации о себе
     */
    public ValidateAccessTokenResponseDto toValidateAccessTokenRestResponse(GatewayUserContext userContext) {
        ValidateAccessTokenResponseDto dto = new ValidateAccessTokenResponseDto();
        dto.setId(userContext.userId());
        dto.setLogin(userContext.login());
        dto.setEmail(userContext.email());
        dto.setDisplayName(userContext.displayName());
        dto.setStatus(userContext.status() != null ? userContext.status().name() : null);
        return dto;
    }

    /**
     * Преобразует перечисление (Enum) статуса пользователя из Protobuf контракта во внутренний доменный статус шлюза.
     * Исключает префиксы gRPC-слоя (например, USER_STATUS_ACTIVE переводит в ACTIVE).
     *
     * @param source автогенерированный статус {@link UserStatus} из gRPC контракта
     * @return соответствующий элемент доменного перечисления шлюза {@link GatewayUserStatus}
     */
    public GatewayUserStatus toGatewayUserStatus(UserStatus source) {
        return switch (source) {
            case USER_STATUS_ACTIVE -> GatewayUserStatus.ACTIVE;
            case USER_STATUS_INVITED -> GatewayUserStatus.INVITED;
            case USER_STATUS_BLOCKED -> GatewayUserStatus.BLOCKED;
            default -> GatewayUserStatus.UNSPECIFIED;
        };
    }

    /**
     * Преобразует перечисление (Enum) глобальной роли пользователя из Protobuf контракта во внутренние глобальные роли шлюза.
     * Исключает префиксы gRPC-слоя (например, GLOBAL_ROLE_USER переводит в USER).
     *
     * @param protoGobalRole автогенерированная глобальная роль {@link GlobalRoleProto} из gRPC контракта
     * @return соответствующий элемент доменного перечисления шлюза {@link GlobalRole}
     */
    public GlobalRole toGlobalRole(GlobalRoleProto protoGlobalRole) {
        return switch (protoGlobalRole) {
            case GLOBAL_ROLE_GLOBAL_ADMIN -> GlobalRole.GLOBAL_ADMIN;
            case GLOBAL_ROLE_USER -> GlobalRole.USER;
            default -> GlobalRole.UNSPECIFIED;
        };
    }
}
