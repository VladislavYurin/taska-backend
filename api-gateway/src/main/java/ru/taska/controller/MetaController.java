package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.MetaApi;
import ru.taska.domain.dto.MetaResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.MetaService;

import static ru.taska.domain.EndpointSecurity.PROTECTED;

/**
 * REST-контроллер API Gateway со словарями допустимых значений перечислений.
 * <p>
 * Ответ строится в gateway без обращения к сервисам. Условный запрос обрабатывает
 * Spring под капотом: для GET-ответа с ETag совпавший {@code If-None-Match} превращается в 304
 * без тела.
 * При этом даже в случае 304 результат всё равно получается из сервиса, но в данном случае это
 * нам ничего не стоит, так как ответ вычисляется один раз при старте.
 * {@code no-cache} заставляет браузер сверять ETag при каждом использовании,
 * а не использовать словари из кеша.
 * <p>
 */
@RestController
@RequiredArgsConstructor
public class MetaController implements MetaApi {

    private final MetaService metaService;
    private final GatewayRequestExecutor executor;

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<MetaResponseDto>> getMeta(
            String ifNoneMatch,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context ->
                Mono.just(ResponseEntity.ok()
                        .eTag(metaService.getEtag())
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .body(metaService.getMeta()))
        );
    }
}
