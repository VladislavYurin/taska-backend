package ru.taska.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/**
 * Protect even validation/error responses emitted before the capabilities controller is entered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantCapabilitiesCacheFilter implements WebFilter {
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (exchange
        .getRequest()
        .getPath()
        .pathWithinApplication()
        .value()
        .matches("/api/v1/tenants/[^/]+/capabilities/?"))
      exchange.getResponse().getHeaders().setCacheControl("private, no-store");
    return chain.filter(exchange);
  }
}
