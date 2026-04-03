package ru.taska.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.taska.domain.UserContext;
import ru.taska.jwt.JwtValidator;

@Component
@Slf4j
public class JwtValidatingGatewayFilterFactory
        extends AbstractGatewayFilterFactory {

    private final JwtValidator jwtValidator;

    public JwtValidatingGatewayFilterFactory(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            log.debug("[jwt-gateway-filter]: Старт фильтрации запроса...");
            // извлекаем заголовки запроса из контекста
            HttpHeaders httpHeaders = exchange.getRequest().getHeaders();

            // если есть заголовок Authorization - извлекаем его body
            if (httpHeaders.containsHeader("Authorization")) {
                log.debug("[jwt-gateway-filter]: Заголовок Authorization найдён, старт проверки"
                                 + " значения заголовка Authorization...");
                String authorizationHeaderBody = httpHeaders.getFirst("Authorization");

                // проверяем, начинается ли body с Bearer, если нет - отбрасываем
                if (authorizationHeaderBody != null &&
                        authorizationHeaderBody.startsWith("Bearer ")) {

                    log.debug("[jwt-gateway-filter]: Структура value заголовка верная, "
                                     + "старт проверки структуры jwt-токена...");

                    // извлекаем токен из тела заголовка Authorization
                    String token = authorizationHeaderBody.substring(7);

                    // проверяем структуру токена - "xxxx.xxxx.xxxx"
                    if (token.matches("^[A-Za-z0-9_-]{2,}(?:\\.[A-Za-z0-9_-]{2,}){2}$")) {

                        log.debug("[jwt-gateway-filter]: Структура токена верная."
                                         + " Начало процесса валидации и проверки подписи...");

                        // данные, извлечённые из jwt
                        Claims tokenClaims;
                        try {
                            tokenClaims = jwtValidator.validateToken(token);
                        } catch (JwtException e) {
                            log.error("[jwt-validator]: валидация токена не удалась: "
                                              + "{}", e.getMessage());
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }


                        // создаём record userContext, который потом и будем прокидывать в gRPC
                        // metadata
                        // пока в userContext только sub (userId)
                        UserContext userContext = new UserContext(tokenClaims.getSubject());

                        // todo add claims to gRPC metadata

                        // пропускаем запрос дальше
                        log.debug("[jwt-gateway-filter]: Токен успешно валидирован. Фильтр "
                                         + "пропускает запрос дальше.");
                        return chain.filter(exchange);
                    }
                }
            }

            // запрос дошёл до конца без валидации - UNAUTHORIZED
            log.error("[jwt-gateway-filter]: Валидация токена не удалась, проблема с форматом "
                             + "заголовка запроса или структурой токена");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
    }

}