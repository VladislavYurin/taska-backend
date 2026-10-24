package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;
import ru.taska.mapper.AuthMapper;
import ru.taska.service.UserService;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final AuthMapper authMapper;
    private final GrpcUserProfileServiceClient profileClient;

    /**
     * Извлекает информацию о текущем пользователе из уже верифицированного контекста безопасности.
     * <p>
     * Основные поля берутся из проверенного контекста; аватар читается отдельным
     * GetUserProfile только для этой операции. Ошибка обогащения не ломает профиль:
     * avatar остаётся null, а avatarUnavailable=true отличает её от отсутствия фото.
     *
     * @param context текущий контекст окружения запроса на Gateway с заполненным профилем пользователя
     * @return {@link Mono} с REST DTO {@link ValidateAccessTokenResponseDto} для ответа клиенту
     */
    @Override
    public Mono<ValidateAccessTokenResponseDto> getMyInfo(GatewayContext context) {
        log.info("[{}] Resolving getMyInfo from context", context.requestId());
        return Mono.defer(() -> {
            var response = authMapper.toValidateAccessTokenRestResponse(context.userContext());
            response.setAvatarUnavailable(false);
            return Mono.defer(() -> profileClient.getUserProfileAvatar(context))
                    .map(avatar -> {
                        response.setAvatar(avatar);
                        return response;
                    })
                    .defaultIfEmpty(response)
                    .onErrorResume(error -> {
                        log.warn("[{}] Could not load current user avatar", context.requestId(), error);
                        response.setAvatar(null);
                        response.setAvatarUnavailable(true);
                        return Mono.just(response);
                    });
        });
    }
}
