package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;
import ru.taska.mapper.AuthMapper;
import ru.taska.service.UserService;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final AuthMapper authMapper;

    /**
     * Извлекает информацию о текущем пользователе из уже верифицированного контекста безопасности.
     * <p>
     * Метод не выполняет внешних gRPC-вызовов, а синхронно маппит данные пользователя,
     * сохраненные в контексте {@link GatewayContext} на этапе предобработки защищенного эндпоинта.
     *
     * @param context текущий контекст окружения запроса на Gateway с заполненным профилем пользователя
     * @return {@link Mono} с REST DTO {@link ValidateAccessTokenResponseDto} для ответа клиенту
     */
    @Override
    public Mono<ValidateAccessTokenResponseDto> getMyInfo(GatewayContext context) {
        log.info("[{}] Resolving getMyInfo from context", context.requestId());
        return Mono.just(authMapper.toValidateAccessTokenRestResponse(context.userContext()));
    }
}
