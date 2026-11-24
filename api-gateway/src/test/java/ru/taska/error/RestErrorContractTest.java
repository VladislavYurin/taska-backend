package ru.taska.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.yaml.snakeyaml.Yaml;

class RestErrorContractTest {
  @Test
  void everyPublishedCompanyRouteUsesProblemContract() throws Exception {
    try (var input = getClass().getResourceAsStream("/static/openapi.yml")) {
      Map<String, Object> spec = new Yaml().load(input);
      var paths = (Map<String, Object>) spec.get("paths");
      var company = paths.keySet().stream()
          .filter(p -> p.startsWith("/api/v1/tenants") || p.startsWith("/api/v1/admin/tenants"))
          .toList();
      assertThat(company).hasSizeGreaterThanOrEqualTo(5);
      for (String path : company) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path.replaceAll("\\{[^}]+}", "id")));
        assertThat(RestErrorContract.usesProblemContract(exchange, Map.of())).as(path).isTrue();
      }
    }
  }

  @Test
  void lifecycleConflictsDoNotChangeErrorsOfNestedOrReadOperations() {
    for (String path : new String[]{"/api/v1/projects/p/members/u", "/api/v1/projects/p/issues", "/api/v1/projects/p"}) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
      assertThat(RestErrorContract.usesProblemContract(exchange, Map.of("code", "VERSION_CONFLICT"))).isFalse();
    }
    var archive = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/v1/projects/p"));
    assertThat(RestErrorContract.usesProblemContract(archive, Map.of("code", "VERSION_CONFLICT"))).isTrue();
    assertThat(RestErrorContract.usesProblemContract(archive, Map.of("reason", "LAST_ADMIN"))).isFalse();
    assertThat(RestErrorContract.usesProblemContract(archive, Map.of("reason", "VERSION_CONFLICT"))).isTrue();
  }
}
