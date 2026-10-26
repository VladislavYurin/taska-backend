package ru.taska.mcp;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("gateway")
public class GatewayHealthIndicator implements ReactiveHealthIndicator {
  private final GatewayClient client;

  public GatewayHealthIndicator(GatewayClient client) {
    this.client = client;
  }

  @Override
  public Mono<Health> health() {
    return client.healthy().map(healthy -> healthy ? Health.up().build() : Health.down().build());
  }
}
